package com.donghaeng.guest

import com.donghaeng.auth.StubGoogleRegistration
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.resource.jdbc.spi.StatementInspector
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.util.concurrent.CopyOnWriteArrayList

/**
 * **The property the whole PATCH-over-PUT argument rests on, held against the SQL
 * that is actually issued** (added 2026-08-22 in review of `#173`).
 *
 * `UpdateWeddingContractTest` asserts that a member the request did not mention
 * still has its old value afterwards — and that assertion is passed just as happily
 * by a blind full-column PUT, because the two PATCHes it makes are two requests and
 * therefore two transactions: the second loads a row that already holds the value
 * and writes the same value back. So it was not holding the property at all. Nor was
 * anything else: before this file, `@DynamicUpdate` appeared in no test in the suite.
 *
 * What actually has to be true is about **one statement**: a request that named one
 * column must not carry the other one into the UPDATE, or a partner editing 예식일
 * blind-writes a 보증인원 their form loaded before the other partner changed it, and
 * `wedding` has no `guest_change` trail to recover it from
 * (notes/2026-08-22-decision-partial-update-shape.md §1).
 *
 * Two mutations were run against this file and each turned it red, which is the
 * only reason it is worth anything: dropping `@DynamicUpdate` from
 * [com.donghaeng.wedding.Wedding] (two of the four below fail), and assigning both
 * columns unconditionally — the blind full-replacement shape a PUT would have — in
 * `WeddingService.update` (one fails). The contract test saw the second mutation
 * too, through `updated_at`, and saw neither of them where it claims to: its "a
 * member that is not sent is left alone" case passed under both.
 *
 * The inspector is installed by property rather than by bean because Hibernate
 * builds it itself, which is also why the capture is static.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.jpa.properties.hibernate.session_factory.statement_inspector=com.donghaeng.guest.CapturedStatements"],
)
@ActiveProfiles("dev")
@Import(StubGoogleRegistration::class)
internal class WeddingUpdateStatementTest : GuestFixture() {
    @Test
    fun `a 보증인원 edit does not carry 예식일 into the UPDATE`() {
        val session = login()
        val weddingId = createWedding(session)
        patch("/weddings/$weddingId", listOf(session), """{"guaranteedHeadcount":150}""")
        CapturedStatements.clear()

        patch("/weddings/$weddingId", listOf(session), """{"guaranteedHeadcount":200}""")

        val update = CapturedStatements.weddingUpdates().single()
        assertThat(update).contains("guaranteed_headcount", "updated_at")
        assertThat(update).doesNotContain("wedding_date")
    }

    @Test
    fun `a 예식일 edit does not carry 보증인원 into the UPDATE`() {
        val session = login()
        val weddingId = createWedding(session)
        patch("/weddings/$weddingId", listOf(session), """{"guaranteedHeadcount":150}""")
        CapturedStatements.clear()

        patch("/weddings/$weddingId", listOf(session), """{"weddingDate":"2027-03-14"}""")

        val update = CapturedStatements.weddingUpdates().single()
        assertThat(update).contains("wedding_date", "updated_at")
        assertThat(update).doesNotContain("guaranteed_headcount")
    }

    @Test
    fun `a body that changes nothing issues no UPDATE at all`() {
        val session = login()
        val weddingId = createWedding(session)
        patch("/weddings/$weddingId", listOf(session), """{"guaranteedHeadcount":150}""")
        CapturedStatements.clear()

        // The same value the row already holds, and an empty body: neither is a
        // change, so neither may move `updated_at`.
        patch("/weddings/$weddingId", listOf(session), """{"guaranteedHeadcount":150}""")
        patch("/weddings/$weddingId", listOf(session), """{}""")

        assertThat(CapturedStatements.weddingUpdates()).isEmpty()
    }

    @Test
    fun `the capture is wired, so the assertions above are not vacuous`() {
        val session = login()
        val weddingId = createWedding(session)
        CapturedStatements.clear()

        patch("/weddings/$weddingId", listOf(session), """{"guaranteedHeadcount":150}""")

        assertThat(CapturedStatements.weddingUpdates()).hasSize(1)
    }
}

/**
 * Hibernate instantiates this by class name, so the capture cannot live on an
 * injected bean. Nothing outside the class above reads it.
 */
internal class CapturedStatements : StatementInspector {
    override fun inspect(sql: String): String {
        SEEN += sql
        return sql
    }

    companion object {
        private val SEEN = CopyOnWriteArrayList<String>()

        fun clear() = SEEN.clear()

        /** UPDATEs of `wedding` itself — not `wedding_party`, which this endpoint never writes. */
        fun weddingUpdates(): List<String> = SEEN.filter { Regex("""^\s*update\s+wedding\s+set""").containsMatchIn(it) }
    }
}
