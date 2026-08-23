package com.donghaeng.wedding

import com.donghaeng.ApiFixture
import com.donghaeng.auth.StubGoogleRegistration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.net.HttpCookie

/**
 * THE RED GATE OF `#132`: **the caller's weddings, from the session alone.**
 *
 * This is the one wedding read that takes no `{weddingId}` — it is what a client
 * calls *before* it has one, which is exactly why both blocked screens need it:
 * `#124` cannot tell "최초 1회" from a returning couple, and `#15` loses the ledger's
 * id on a refresh (`notes/2026-08-07-design-screens-and-flow.md`).
 *
 * **Nothing scopes it but the caller**, so the tenancy assertion here is doing the
 * work `CurrentWeddingArgumentResolver` does everywhere else. A seat predicate
 * dropped from the query does not 404 anyone — it hands every couple every other
 * couple's ledger in a 200 — so the outsider below owns a wedding of their own, per
 * the rule `#5`'s audit left behind (notes/2026-08-19-decision-wedding-scope-gate.md
 * §2b): a tenancy test whose refused caller is a member of nothing proves nothing.
 *
 * The two exclusions are one test each, because they fail in opposite directions and
 * neither shows up in the other: a soft-deleted wedding still has a live seat
 * pointing at it, and a released seat still points at a live wedding
 * (notes/2026-08-10-decision-soft-delete.md).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Import(StubGoogleRegistration::class)
internal class WeddingListContractTest : ApiFixture() {
    @Autowired private lateinit var jdbc: JdbcTemplate

    /** Both ends, and for the reason `CurrentWeddingContractTest` gives: the container is shared. */
    @BeforeEach
    @AfterEach
    fun clean() {
        jdbc.update("delete from wedding_subscription")
        jdbc.update("delete from wedding_party")
        jdbc.update("delete from wedding")
    }

    @Test
    fun `a caller who has no wedding gets an empty list, not a 404`() {
        // The "최초 1회" answer. It is an ordinary 200 on purpose: a client that had
        // to read a 404 as "you have none" would have to tell it apart from every
        // other 404 this API serves, and the wedding-scoped ones are deliberately
        // indistinguishable from each other.
        val response = get("/weddings", listOf(login()))

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json")
        assertThat(response.json().toList()).isEmpty()
    }

    @Test
    fun `an anonymous request is 401, never an empty list`() {
        // `authorizeHttpRequests` is `permitAll` everywhere, so an authenticated
        // caller is the only thing standing between this endpoint and an anonymous
        // one (notes/2026-08-10-decision-auth-gate-and-sequence.md). Answering `[]`
        // would be indistinguishable from a correct answer to a logged-in newcomer,
        // which is how a scopeless endpoint stops being noticed.
        val response = get("/weddings")

        assertThat(response.statusCode()).isEqualTo(401)
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json")
        assertThat(response.json()["code"].asText()).isEqualTo("UNAUTHENTICATED")
    }

    @Test
    fun `the list is the caller's weddings and nobody else's`() {
        val session = login()
        val mine = createWedding(session, "김신랑")

        val outsider = loginAs("someone-else")
        val theirs = createWedding(outsider, "박신랑")

        val response = get("/weddings", listOf(session))

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.json().map { it["id"].asLong() }).containsExactly(mine).doesNotContain(theirs)
    }

    @Test
    fun `an entry carries the same shape POST returns`() {
        val session = login()
        val id = createWedding(session, "김신랑")

        val entry = get("/weddings", listOf(session)).json().single()

        assertThat(entry["id"].asLong()).isEqualTo(id)
        assertThat(entry["weddingDate"].asText()).isEqualTo("2026-10-10")
        assertThat(entry["seats"].map { it["side"].asText() }).containsExactly("GROOM", "BRIDE")
        assertThat(entry["seats"][0]["name"].asText()).isEqualTo("김신랑")
        assertThat(entry.fieldNames().asSequence().toList())
            .containsExactlyInAnyOrder("id", "weddingName", "weddingDate", "seats")
    }

    @Test
    fun `a soft-deleted wedding is not in the list, though the seat is still live`() {
        val session = login()
        val deleted = createWedding(session, "김신랑")

        // **The control is the same list one line earlier, and it is the test.**
        // The expected answer after the delete is an EMPTY list, which a query that
        // had stopped returning anything would also give; a second live wedding used
        // to play that part, and `ux_party_user` has made one impossible
        // (notes/2026-08-21-decision-one-wedding-per-person.md).
        assertThat(listed(session)).containsExactly(deleted)

        jdbc.update("update wedding set deleted_at = now() where id = ?", deleted)

        assertThat(listed(session)).isEmpty()
    }

    @Test
    fun `a wedding whose seat was released is not in the list, though the wedding is live`() {
        // A removed partner keeps nothing. The wedding row is untouched here, so
        // only the seat predicate can exclude it — and the control above is what says
        // the predicate excluded it rather than the query excluding everything.
        val session = login()
        val left = createWedding(session, "김신랑")

        assertThat(listed(session)).containsExactly(left)

        jdbc.update("update wedding_party set deleted_at = now() where wedding_id = ?", left)

        assertThat(listed(session)).isEmpty()
    }

    // `two weddings come back newest first` was deleted on 2026-08-21 with
    // `ux_membership_user` — now `ux_party_user` — and this line is here so the
    // deletion is not read as a gap. The order is not reachable to assert any more:
    // it needs one caller holding two live seats, which is the row the index refuses,
    // so the test could only have been kept by building a state no database of ours
    // can hold.
    // `WeddingRepository.findAllLiveForMember` keeps its `order by` — see its KDoc
    // for what that is now worth.

    private fun listed(session: HttpCookie): List<Long> = get("/weddings", listOf(session)).json().map { it["id"].asLong() }
}
