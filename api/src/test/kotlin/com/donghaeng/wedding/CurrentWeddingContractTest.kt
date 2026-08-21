package com.donghaeng.wedding

import ch.qos.logback.classic.Level
import com.donghaeng.ApiFixture
import com.donghaeng.auth.StubGoogleRegistration
import com.donghaeng.capturingLog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles

/**
 * THE RED GATE OF `#5`: `user → seat → wedding` resolution, observed on the
 * first wedding-scoped endpoint in the product.
 *
 * **The resolver is the gate, and this is what makes that sentence checkable.**
 * `authorizeHttpRequests` is `permitAll` in every environment, including production
 * (notes/2026-08-10-decision-auth-gate-and-sequence.md), so nothing in the filter
 * chain refuses anything — what refuses a request is resolution failing. The four
 * ways it can fail are one test each below, and each was earned by an audit rather
 * than derived:
 *
 * 1. **anonymous → 401**, because `permitAll` would otherwise let a stranger in.
 * 2. **an authenticated non-member → 404, never 403**, and byte-for-byte the answer
 *    a wedding that does not exist gets, or the pair is a wedding-id oracle
 *    (notes/2026-08-10-decision-cross-tenant-status-code.md).
 * 3. **a soft-deleted wedding with a LIVE seat → 404.** The partial indexes filter
 *    `wedding_party.deleted_at`; nothing filtered `wedding.deleted_at`, and a
 *    resolution that walks a live seat onto a dead wedding opens the gate one
 *    condition at a time (notes/2026-08-10-decision-soft-delete.md).
 * 4. **an id that is not a number → 404**, because the resolver owns the path
 *    variable: no handler may take it, so no handler's converter can answer first.
 *
 * Driven over real HTTP against a real Postgres, with sessions earned by completing
 * actual OAuth round trips. A test calling the resolver directly would pass with it
 * unwired, which is the failure the auth-gate record says must not be shippable.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Import(StubGoogleRegistration::class)
internal class CurrentWeddingContractTest : ApiFixture() {
    @Autowired private lateinit var weddings: WeddingRepository

    @Autowired private lateinit var seats: WeddingSeatRepository

    @Autowired private lateinit var jdbc: JdbcTemplate

    /**
     * Both ends, and the second half is not tidiness: the container is shared with
     * every other test class and `wedding.created_by` references `app_user`, so a
     * wedding left behind here fails the `users.deleteAll()` in the login tests with
     * a foreign-key violation, in whichever class happens to run next. `deleteAll()`
     * cannot see a soft-deleted row, so the sweep is SQL.
     */
    @BeforeEach
    @AfterEach
    fun clean() {
        jdbc.update("delete from wedding_subscription")
        jdbc.update("delete from wedding_party")
        jdbc.update("delete from wedding")
    }

    @Test
    fun `a member reads their own wedding`() {
        val session = login()
        val weddingId = createWedding(session)

        val response = get("/weddings/$weddingId", listOf(session))

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json")
        assertThat(response.json()["id"].asLong()).isEqualTo(weddingId)
        assertThat(response.json()["weddingDate"].asText()).isEqualTo("2026-10-10")
        // The couple's names live on the seats as of 2026-08-22; the wedding carries
        // none of its own, and this is the shape the ledger header reads.
        assertThat(response.json()["seats"].map { it["side"].asText() }).containsExactly("GROOM", "BRIDE")
        assertThat(response.json()["seats"][0]["name"].asText()).isEqualTo("김신랑")
        assertThat(response.json()["seats"][1]["name"].isNull).describedAs("the partner has not arrived").isTrue()
    }

    @Test
    fun `an anonymous request to a wedding-scoped endpoint is 401`() {
        val weddingId = createWedding(login())

        val response = get("/weddings/$weddingId")

        assertThat(response.statusCode()).isEqualTo(401)
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json")
        assertThat(response.json()["code"].asText()).isEqualTo("UNAUTHENTICATED")
    }

    @Test
    fun `an authenticated non-member is 404, and is told exactly what a stranger id is told`() {
        val weddingId = createWedding(login())

        // **The outsider gets a wedding of their own first**, and that line is the
        // test. Without it, "not a member of THIS wedding" and "not a member of
        // anything" are the same state to the whole suite — so the resolver could
        // drop `weddingId` from its seat query, handing every couple every
        // other couple's ledger, and stay green. Verified by making exactly that
        // mutation and watching this go red.
        val outsider = loginAs("someone-else")
        createWedding(outsider)

        val refused = get("/weddings/$weddingId", listOf(outsider))
        val nonexistent = get("/weddings/${weddingId + 10_000}", listOf(outsider))

        assertThat(refused.statusCode()).isEqualTo(404)
        assertThat(refused.headers().firstValue("Content-Type")).hasValue("application/problem+json")

        // The whole point of 404 over 403: the two answers must be indistinguishable,
        // or a session holder can walk the id space and learn which weddings exist.
        // Compared member by member rather than as a status code, because `code`,
        // `detail` and `title` are just as observable as the status is — `instance`
        // is the request's own path and is the one member that must differ.
        assertThat(withoutInstance(refused)).isEqualTo(withoutInstance(nonexistent))
        assertThat(refused.json()["code"].asText()).isEqualTo("WEDDING_NOT_FOUND")
    }

    @Test
    fun `a soft-deleted wedding is 404 to a member whose seat is still live`() {
        val session = login()
        val weddingId = createWedding(session)

        jdbc.update("update wedding set deleted_at = now() where id = ?", weddingId)

        // The seat is untouched and still resolves on its own — which is precisely
        // the hole: `user → seat` succeeds, and only a resolver that also looks at
        // the wedding refuses.
        assertThat(seats.findAll().map { it.weddingId }).contains(weddingId)

        val (response, log) = capturingLog { get("/weddings/$weddingId", listOf(session)) }

        assertThat(response.statusCode()).isEqualTo(404)
        assertThat(response.json()["code"].asText()).isEqualTo("WEDDING_NOT_FOUND")

        // **The RESOLVER has to be what refused it**, and the status alone cannot say
        // so: this endpoint reads the wedding, so it answers 404 to a deleted one
        // whether or not resolution looked at `deleted_at` — verified by deleting the
        // condition and watching this test stay green without it. Every OTHER
        // wedding-scoped endpoint reads something else, and for those the resolver is
        // the only thing between a deleted wedding and its guest list. The refusal
        // mark is only set on the resolution path, so it is what tells the two apart.
        assertThat(log.everything().map { it.formattedMessage })
            .anyMatch { "/weddings/$weddingId" in it && "wedding scope refused" in it }
    }

    @Test
    fun `a wedding id that is not a number is 404, not a 400 and not a masked 500`() {
        // No handler takes `{weddingId}`, so nothing converts it before the resolver
        // does — and the resolver answers the same thing it answers for an id nobody
        // owns. A 400 here would be a different answer for an id that cannot exist
        // than for one that merely does not, which is the oracle again in miniature.
        val response = get("/weddings/not-a-number", listOf(login()))

        assertThat(response.statusCode()).isEqualTo(404)
        assertThat(response.json()["code"].asText()).isEqualTo("WEDDING_NOT_FOUND")
    }

    @Test
    fun `a refused resolution is distinguishable in the log, though not in the response`() {
        // The cost of answering 404: a seat-resolution failure and a typo'd id look
        // identical from outside, so the server-side signal has to carry what the
        // response deliberately does not
        // (notes/2026-08-10-decision-cross-tenant-status-code.md). This is the input
        // the security record's alerting on 401/404/429 spikes needs to tell a walk
        // over the id space apart from every other 404 this API serves.
        val weddingId = createWedding(login())
        // A member of a DIFFERENT wedding, for the reason the test above gives.
        val outsider = loginAs("someone-else")
        createWedding(outsider)

        val (response, log) = capturingLog { get("/weddings/$weddingId", listOf(outsider)) }

        assertThat(response.statusCode()).isEqualTo(404)
        val record = log.everything().single { "/weddings/$weddingId" in it.formattedMessage }
        assertThat(record.level).isEqualTo(Level.INFO)
        assertThat(record.formattedMessage).contains("wedding scope refused")

        // Status and path and the mark, and nothing else — never the body, never a
        // header, never who was asking.
        assertThat(record.formattedMessage).doesNotContain("DH_SESSION")
    }
}
