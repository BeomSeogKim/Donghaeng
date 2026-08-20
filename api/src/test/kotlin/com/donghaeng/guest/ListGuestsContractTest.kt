package com.donghaeng.guest

import com.donghaeng.ApiFixture
import com.donghaeng.auth.STUB_PROVIDER
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
import java.net.http.HttpResponse

/**
 * THE RED GATE OF `#147` (the backend half of `#15`): **the ledger, which is the
 * screen the rest of v1 opens on top of**
 * (notes/2026-08-07-design-screens-and-flow.md).
 *
 * Three of the assertions below are the seam rather than the endpoint, and they are
 * the reason this is a contract test over real HTTP: `web/` builds its ledger from
 * the response shape, its filter chips from the two parameters' value sets, and its
 * scrolling from whether this endpoint pages. The last one cannot be asserted by
 * looking at the code — a `Pageable` slipped into the signature would still answer
 * 200 with an array — so `the whole ledger comes back in one response` inserts more
 * rows than any default page size and counts what comes back.
 *
 * The attendance filter is the one that needs a database to be honest about: it
 * reads `coalesce(confirmed, expected)`, which is the rule the headcount sums
 * (notes/2026-08-05-design-meal-headcount.md §1), and **nothing in v1 writes a
 * confirmed value yet** (`#13`). So the rows that exercise it are written straight
 * through JDBC — a filter that quietly read `expected_attending` alone would agree
 * with every request this suite could otherwise make, and then disagree with the
 * number on the same screen the day `#13` lands.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Import(StubGoogleRegistration::class)
internal class ListGuestsContractTest : ApiFixture() {
    @Autowired private lateinit var jdbc: JdbcTemplate

    /** Both ends, in FK order, for the reason `CreateGuestContractTest` gives. */
    @BeforeEach
    @AfterEach
    fun clean() {
        jdbc.update("delete from guest")
        jdbc.update("delete from membership")
        jdbc.update("delete from wedding")
    }

    @Test
    fun `the ledger is a bare array of the same rows POST returns, oldest first`() {
        val session = login()
        val weddingId = createWedding(session)
        val first = addGuest(session, weddingId, """{"name":"김영수","side":"GROOM"}""")
        val second = addGuest(session, weddingId, """{"name":"이영희","side":"BRIDE"}""")
        val third = addGuest(session, weddingId, """{"name":"박민수","side":"GROOM"}""")

        val response = get("/weddings/$weddingId/guests", listOf(session))

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json")

        // Entry order, and it is contract: the couple scans a list they built, and
        // a row that moves between reads is a row they tap by mistake.
        assertThat(ids(response)).containsExactly(first, second, third)

        // The same `GuestResponse` the create returns — one type for both, so `web/`
        // caches one shape. The confirmed slots are still absent, as
        // `docs/api-spec.md` says: nothing writes them until `#13`.
        val members =
            response
                .json()
                .first()
                .fieldNames()
                .asSequence()
                .toList()
        assertThat(members)
            .containsExactlyInAnyOrder(
                "id",
                "name",
                "side",
                "groupCategory",
                "groupLabel",
                "contact",
                "accessibilityNote",
                "expectedAttending",
                "expectedPartySize",
            )
    }

    @Test
    fun `a wedding with no guests answers an empty array, not a 404`() {
        // The state every couple's ledger starts in, and the one `#148` renders its
        // empty state from. A 404 here would be indistinguishable from the four
        // situations `WEDDING_NOT_FOUND` already covers.
        val session = login()
        val weddingId = createWedding(session)

        val response = get("/weddings/$weddingId/guests", listOf(session))

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.json().toList()).isEmpty()
    }

    @Test
    fun `side narrows the list, and an omitted side narrows nothing`() {
        val session = login()
        val weddingId = createWedding(session)
        val groom = addGuest(session, weddingId, """{"name":"김영수","side":"GROOM"}""")
        val bride = addGuest(session, weddingId, """{"name":"이영희","side":"BRIDE"}""")

        assertThat(ids(list(session, weddingId, "?side=GROOM"))).containsExactly(groom)
        assertThat(ids(list(session, weddingId, "?side=BRIDE"))).containsExactly(bride)
        assertThat(ids(list(session, weddingId, ""))).containsExactly(groom, bride)

        // An empty value is no filter rather than a 400, so a client may send the
        // parameter unconditionally from a cleared chip.
        assertThat(ids(list(session, weddingId, "?side="))).containsExactly(groom, bride)
    }

    @Test
    fun `attendance reads the confirmed value when there is one, and the expected value otherwise`() {
        // The load-bearing test of this endpoint. The ledger and the headcount are
        // one screen, so "참석" here has to mean what the number counts —
        // confirmed if we have it, else what the couple typed
        // (notes/2026-08-05-design-meal-headcount.md §1). Read expected-only, this
        // filter would put a guest in the 참석 chip while the number counts them 불참.
        val session = login()
        val weddingId = createWedding(session)
        val expectedAttending = addGuest(session, weddingId, """{"name":"김영수","side":"GROOM"}""")
        val expectedNot = addGuest(session, weddingId, """{"name":"이영희","side":"BRIDE","expectedAttending":false}""")
        val confirmedNot = addGuest(session, weddingId, """{"name":"박민수","side":"GROOM"}""")
        val confirmedYes = addGuest(session, weddingId, """{"name":"최지우","side":"BRIDE","expectedAttending":false}""")

        // `#13` is what will write these; today only the database can.
        confirm(confirmedNot, attending = false)
        confirm(confirmedYes, attending = true)

        assertThat(ids(list(session, weddingId, "?attendance=ATTENDING")))
            .containsExactly(expectedAttending, confirmedYes)
        assertThat(ids(list(session, weddingId, "?attendance=NOT_ATTENDING")))
            .containsExactly(expectedNot, confirmedNot)
        assertThat(ids(list(session, weddingId, ""))).hasSize(4)
    }

    @Test
    fun `the two filters compose`() {
        val session = login()
        val weddingId = createWedding(session)
        val wanted = addGuest(session, weddingId, """{"name":"김영수","side":"GROOM"}""")
        addGuest(session, weddingId, """{"name":"이영희","side":"BRIDE"}""")
        addGuest(session, weddingId, """{"name":"박민수","side":"GROOM","expectedAttending":false}""")

        assertThat(ids(list(session, weddingId, "?side=GROOM&attendance=ATTENDING"))).containsExactly(wanted)
    }

    @Test
    fun `group is not a filter, and sending one narrows nothing`() {
        // A decided exclusion, not an omission: a group is an axis the couple reads
        // in the aggregate, and `groupLabel` fractures on typing variants, so
        // neither may narrow the list (`#15`, notes/2026-08-06-design-ledger-and-import.md §1).
        // An unknown query parameter is ignored, so a client that sends one gets the
        // whole ledger rather than a silently wrong subset — asserted because the
        // failure mode is invisible from the response alone.
        val session = login()
        val weddingId = createWedding(session)
        val friend = addGuest(session, weddingId, """{"name":"김영수","side":"GROOM","groupCategory":"FRIEND"}""")
        val family = addGuest(session, weddingId, """{"name":"이영희","side":"BRIDE","groupCategory":"FAMILY"}""")

        assertThat(ids(list(session, weddingId, "?groupCategory=FRIEND"))).containsExactly(friend, family)
    }

    @Test
    fun `a filter value outside its set is 400`() {
        val session = login()
        val weddingId = createWedding(session)
        addGuest(session, weddingId, """{"name":"김영수","side":"GROOM"}""")

        // `UNKNOWN` is in the list on purpose: it is the one value a client is
        // likeliest to try, and its refusal is what the spec's "미확인 is a second
        // axis, not a third value" sentence promises.
        listOf("?side=BOTH", "?attendance=MAYBE", "?attendance=UNKNOWN").forEach { query ->
            val response = list(session, weddingId, query)
            assertThat(response.statusCode()).describedAs(query).isEqualTo(400)
            assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json")
            // The code `docs/api-spec.md` publishes for this — a framework-level
            // 400 falls back to the status name, and the frontend switches on it.
            assertThat(response.json()["code"].asText()).describedAs(query).isEqualTo("BAD_REQUEST")
        }
    }

    @Test
    fun `a soft-deleted guest is not in the list`() {
        val session = login()
        val weddingId = createWedding(session)
        val kept = addGuest(session, weddingId, """{"name":"김영수","side":"GROOM"}""")
        val removed = addGuest(session, weddingId, """{"name":"이영희","side":"BRIDE"}""")

        jdbc.update("update guest set deleted_at = now() where id = ?", removed)

        assertThat(ids(list(session, weddingId, ""))).containsExactly(kept)
        // The filtered path is a separate query shape, and a filter written by hand
        // is exactly where the ambient restriction stops being enough
        // (notes/2026-08-10-decision-soft-delete.md, consequence 1).
        assertThat(ids(list(session, weddingId, "?side=BRIDE"))).isEmpty()
    }

    @Test
    fun `the list is this wedding's guests and nobody else's`() {
        val session = login()
        val first = createWedding(session)
        val second = createWedding(session)
        val here = addGuest(session, first, """{"name":"김영수","side":"GROOM"}""")
        val there = addGuest(session, second, """{"name":"이영희","side":"BRIDE"}""")

        // A member of two weddings is the case a query scoped to "the caller's
        // guests" passes and a wedding-scoped one gets right — the accepted half of
        // the tenancy question (notes/2026-08-19-decision-wedding-scope-gate.md §2b).
        assertThat(ids(list(session, first, ""))).containsExactly(here).doesNotContain(there)
        assertThat(ids(list(session, second, ""))).containsExactly(there)
    }

    @Test
    fun `a logged-in stranger is told exactly what a nonexistent wedding is told`() {
        val session = login()
        val weddingId = createWedding(session)
        addGuest(session, weddingId, """{"name":"김영수","side":"GROOM"}""")

        // The outsider owns a wedding of their own, or "not a member of THIS
        // wedding" and "not a member of anything" are the same state and a resolver
        // that dropped the wedding id would stay green.
        val outsider = loginAs("someone-else")
        createWedding(outsider)

        val refused = list(outsider, weddingId, "")
        val nonexistent = list(outsider, weddingId + 10_000, "")

        assertThat(refused.statusCode()).isEqualTo(404)
        assertThat(refused.json()["code"].asText()).isEqualTo("WEDDING_NOT_FOUND")
        assertThat(withoutInstance(refused)).isEqualTo(withoutInstance(nonexistent))
    }

    @Test
    fun `an anonymous request is 401, whatever it asks for`() {
        val session = login()
        val weddingId = createWedding(session)

        val response = get("/weddings/$weddingId/guests?side=GROOM")

        assertThat(response.statusCode()).isEqualTo(401)
        assertThat(response.json()["code"].asText()).isEqualTo("UNAUTHENTICATED")
    }

    @Test
    fun `a soft-deleted wedding answers 404, though the membership is live`() {
        val session = login()
        val weddingId = createWedding(session)
        addGuest(session, weddingId, """{"name":"김영수","side":"GROOM"}""")
        jdbc.update("update wedding set deleted_at = now() where id = ?", weddingId)

        val response = list(session, weddingId, "")

        assertThat(response.statusCode()).isEqualTo(404)
        assertThat(response.json()["code"].asText()).isEqualTo("WEDDING_NOT_FOUND")
    }

    @Test
    fun `the whole ledger comes back in one response — there is no page`() {
        // **The pagination answer, asserted rather than described** (docs/api-spec.md
        // carries the reasoning). A real ledger is 200–800 rows and the couple scans
        // it; every default page size in this stack is smaller than that, so a
        // `Pageable` that slipped into the signature would answer 200 with the first
        // twenty rows and nothing else here would notice.
        //
        // Written through JDBC because 300 HTTP round trips would make this the
        // slowest test in the suite for no extra coverage.
        val session = login()
        val weddingId = createWedding(session)
        val userId = me(session)
        insertGuests(weddingId, userId, count = 300)

        val response = list(session, weddingId, "")

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.json().toList()).hasSize(300)
        // A bare array, so there is nowhere for a page cursor to hide either.
        assertThat(response.json().isArray).isTrue()
    }

    private fun list(
        session: HttpCookie,
        weddingId: Long,
        query: String,
    ): HttpResponse<String> = get("/weddings/$weddingId/guests$query", listOf(session))

    private fun ids(response: HttpResponse<String>): List<Long> = response.json().map { it["id"].asLong() }

    private fun addGuest(
        session: HttpCookie,
        weddingId: Long,
        body: String,
    ): Long =
        post("/weddings/$weddingId/guests", listOf(session), body)
            .json()["guest"]["id"]
            .asLong()

    /** What `#13` will do through the API, and what nothing else can do today. */
    private fun confirm(
        guestId: Long,
        attending: Boolean,
    ) = jdbc.update("update guest set confirmed_attending = ? where id = ?", attending, guestId)

    private fun insertGuests(
        weddingId: Long,
        userId: Long,
        count: Int,
    ) = repeat(count) { index ->
        jdbc.update(
            """
            insert into guest (wedding_id, name, side, group_category, expected_attending, expected_party_size,
                               created_by, created_at, updated_by, updated_at)
            values (?, ?, 'GROOM'::wedding_side, 'OTHER', true, 1, ?, now(), ?, now())
            """.trimIndent(),
            weddingId,
            "하객$index",
            userId,
            userId,
        )
    }

    private fun withoutInstance(response: HttpResponse<String>): Map<*, *> =
        mapper.readValue(response.body(), Map::class.java).filterKeys { it != "instance" }

    private fun me(session: HttpCookie): Long = get("/auth/me", listOf(session)).json()["id"].asLong()

    private fun createWedding(session: HttpCookie): Long =
        post(
            "/weddings",
            listOf(session),
            """{"weddingDate":"2026-10-10","groomName":"김신랑","brideName":"이신부"}""",
        ).json()["id"]
            .asLong()

    /** A second person, with their own `app_user` row and their own session. */
    private fun loginAs(subject: String): HttpCookie {
        STUB_PROVIDER.subject = subject
        return login()
    }
}
