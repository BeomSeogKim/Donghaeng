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
import java.net.http.HttpResponse
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * THE RED GATE OF `#123`: a logged-in person creates a wedding, and comes away with
 * a wedding **and a membership in it**.
 *
 * The second row is what this stop is about. Every later request resolves
 * `user → membership → wedding` (`#5`, sequenced after this issue precisely because
 * before it there is no membership to resolve), so a wedding written without one is
 * a ledger nobody can open — and the onboarding screen creates a wedding once, so
 * there is no second attempt that would fix it.
 *
 * Driven over real HTTP against a real Postgres carrying `V1`+`V2`, with a session
 * earned by completing an actual OAuth round trip ([ApiFixture.login]). A test that
 * called [WeddingService] directly would pass with the argument resolver unwired,
 * which is exactly the failure the auth-gate record says must not be possible to
 * ship.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@ActiveProfiles("dev")
@Import(StubGoogleRegistration::class)
internal class CreateWeddingContractTest : ApiFixture() {
    @Autowired private lateinit var weddings: WeddingRepository

    @Autowired private lateinit var memberships: MembershipRepository

    @Autowired private lateinit var jdbc: JdbcTemplate

    /**
     * Both ends, and the second half is not tidiness: the container is shared with
     * every other test class, and `wedding.created_by` references `app_user`. A
     * wedding left behind here makes the `users.deleteAll()` in the login tests fail
     * with a foreign-key violation — in whichever class happens to run next.
     */
    @BeforeEach
    @AfterEach
    fun clean() {
        // SQL, not `deleteAll()`: both entities carry `@SQLRestriction`, so a
        // repository delete cannot see the soft-deleted rows these tests make — and
        // a membership left behind fails the `wedding` delete on its foreign key.
        jdbc.update("delete from membership")
        jdbc.update("delete from wedding")
    }

    @Test
    fun `a logged-in person creates a wedding, and the membership that reaches it`() {
        val session = login()
        val userId = callerId(session)

        val response = create(session, body())

        assertThat(response.statusCode()).isEqualTo(201)
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json")

        // The response is the created resource, with the id the client did not have.
        val created = response.json()
        assertThat(created["weddingDate"].asText()).isEqualTo("2026-10-10")
        assertThat(created["groomName"].asText()).isEqualTo("김신랑")
        assertThat(created["brideName"].asText()).isEqualTo("이신부")
        assertThat(created["id"].asLong()).isPositive()

        // BOTH rows, in one request. The membership is the half a reviewer cannot
        // see from the response, so it is asserted from the database.
        val wedding = weddings.findAll().single()
        assertThat(wedding.id).isEqualTo(created["id"].asLong())
        assertThat(wedding.createdBy).isEqualTo(userId)

        val membership = memberships.findAll().single()
        assertThat(membership.weddingId).isEqualTo(wedding.id)
        assertThat(membership.userId).isEqualTo(userId)
    }

    @Test
    fun `보증인원 is not asked here, and cannot be smuggled in`() {
        val session = login()

        val response = create(session, body(extra = ""","guaranteedHeadcount":250"""))

        // Refusing the member would be a nicer story, but Jackson is configured to
        // ignore unknown ones and this asserts the consequence rather than the
        // configuration: whatever a client sends, the venue's number is still unset.
        assertThat(response.statusCode()).isEqualTo(201)
        assertThat(response.json().has("guaranteedHeadcount")).isFalse()
        assertThat(headcountOf(response.json()["id"].asLong())).isNull()
    }

    @Test
    fun `an anonymous request is 401, and is refused before its body is read`() {
        // The body is invalid on purpose. An anonymous caller must get one answer,
        // not one that depends on what they sent — which is a statement about the
        // ORDER of the handler's parameters, and it fails if they are swapped.
        val response = create(cookies = emptyList(), body = """{"groomName":""}""")

        assertThat(response.statusCode()).isEqualTo(401)
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json")
        assertThat(response.json()["code"].asText()).isEqualTo("UNAUTHENTICATED")
        assertThat(weddings.findAll()).isEmpty()
        assertThat(memberships.findAll()).isEmpty()
    }

    @Test
    fun `a name that is blank, whitespace, or too long for its column is 400`() {
        val session = login()

        // `varchar(100)`, and the column is not the validator: unvalidated, the 101st
        // character is refused by Postgres, i.e. as a masked 500
        // (notes/2026-08-17-decision-log-masking-mechanism.md).
        val tooLong = "가".repeat(101)
        val rejected =
            listOf(
                body(groom = ""),
                body(groom = "   "),
                body(bride = ""),
                body(groom = tooLong),
                body(bride = tooLong),
            )

        rejected.forEach { body ->
            val response = create(session, body)
            assertThat(response.statusCode()).describedAs(body).isEqualTo(400)
            assertThat(response.json()["code"].asText()).describedAs(body).isEqualTo("VALIDATION_FAILED")
        }

        // A name one character shorter is a real name and is accepted, so the bound
        // above is the column's and not an off-by-one of its own.
        assertThat(create(session, body(groom = "가".repeat(100))).statusCode()).isEqualTo(201)

        assertThat(weddings.findAll()).hasSize(1)
    }

    @Test
    fun `the length limit is measured on what was sent, before the trim`() {
        // Stated as a test because it is a choice, and `docs/api-spec.md` says the
        // same thing to `web/`. Trimming first would need every string field on
        // fifteen DTOs to remember a custom deserialiser — a rule that fails silently
        // when forgotten — to buy a case that only bites at exactly 101 characters
        // whose last one is a space.
        val response = create(login(), body(groom = "가".repeat(100) + " "))

        assertThat(response.statusCode()).isEqualTo(400)
        assertThat(response.json()["code"].asText()).isEqualTo("VALIDATION_FAILED")
    }

    @Test
    fun `a date the column cannot hold is 400, not a 500 from Postgres`() {
        // `LocalDate` spans ±999999999 years and Jackson accepts an expanded year, so
        // without @StorableDate these deserialise cleanly and are refused by
        // PostgreSQL — as a masked 500, from the endpoint's point of view a fault.
        val session = login()

        listOf("+5874898-01-01", "+999999999-12-31").forEach { date ->
            val response = create(session, body(date = date))
            assertThat(response.statusCode()).describedAs(date).isEqualTo(400)
            assertThat(response.json()["code"].asText()).describedAs(date).isEqualTo("VALIDATION_FAILED")
        }

        // The bound is the column's own, so the last date it can hold is a 201 — which
        // is what stops the constraint from quietly becoming a product rule nobody
        // decided.
        assertThat(create(session, body(date = "+5874897-12-31")).statusCode()).isEqualTo(201)
        assertThat(weddings.findAll()).hasSize(1)
    }

    @Test
    fun `an omitted field is 400 too, and says the body could not be read`() {
        // A different `code` from the case above, and the spec says so rather than
        // this being smoothed over: the fields are non-null Kotlin types, so an
        // omitted one fails while the body is being deserialised.
        val response = create(login(), """{"groomName":"김신랑","brideName":"이신부"}""")

        assertThat(response.statusCode()).isEqualTo(400)
        assertThat(response.json()["code"].asText()).isEqualTo("MALFORMED_REQUEST_BODY")
        assertThat(weddings.findAll()).isEmpty()
    }

    @Test
    fun `a second wedding by the same person is refused`() {
        // REVERSES what this test asserted until 2026-08-21, when the founder
        // settled that a person belongs to exactly one wedding — created or joined,
        // never both, never two (root AGENTS.md, Standing product facts).
        //
        // The screen already refuses it (`#148`), and a screen is bypassed by one
        // `curl`. What follows a second wedding is not a tidy extra row: `web/`
        // reads the caller's FIRST wedding as "the ledger", and there is no switcher
        // and no delete — so the second one makes the first one's 하객 unreachable.
        val session = login()

        val first = create(session, body())
        val second = create(session, body(date = "2027-03-03"))

        assertThat(first.statusCode()).isEqualTo(201)
        assertThat(second.statusCode()).isEqualTo(409)
        assertThat(second.headers().firstValue("Content-Type")).hasValue("application/problem+json")
        assertThat(second.json()["code"].asText()).isEqualTo("ALREADY_IN_A_WEDDING")

        // The refusal is total: the second request leaves no wedding behind either,
        // which is what a check placed after the insert would get wrong.
        assertThat(weddings.findAll().map { it.id }).containsExactly(first.json()["id"].asLong())
        assertThat(memberships.findAll()).hasSize(1)
    }

    @Test
    fun `a person whose only membership was soft-deleted creates again`() {
        // The case a bare `exists` query gets wrong. Every membership is soft-deleted
        // (notes/2026-08-10-decision-soft-delete.md), so a person removed from the
        // wedding they were in still has a row — and if that row counted, they could
        // never have a ledger again.
        val session = login()
        val abandoned = create(session, body()).json()["id"].asLong()
        jdbc.update("update membership set deleted_at = now() where wedding_id = ?", abandoned)

        val second = create(session, body(date = "2027-03-03"))

        assertThat(second.statusCode()).isEqualTo(201)
        assertThat(second.json()["id"].asLong()).isNotEqualTo(abandoned)
    }

    @Test
    fun `simultaneous creates leave exactly one wedding`() {
        // Two tabs, or one button double-tapped on a slow connection. A read-then-
        // insert inside the service is a race with a real window: every request
        // finds no membership, and every request creates one — after which the
        // person's ledger is whichever wedding `GET /weddings` happens to sort
        // first. Driven over HTTP because the window is between transactions.
        val session = login()
        val ready = CyclicBarrier(SIMULTANEOUS)
        val pool = Executors.newFixedThreadPool(SIMULTANEOUS)

        val statuses =
            try {
                (1..SIMULTANEOUS)
                    .map { attempt ->
                        pool.submit<Int> {
                            ready.await(10, TimeUnit.SECONDS)
                            create(session, body(groom = "김신랑$attempt")).statusCode()
                        }
                    }.map { it.get(30, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }

        assertThat(statuses.count { it == 201 }).describedAs("%s", statuses).isEqualTo(1)
        assertThat(statuses.count { it == 409 }).describedAs("%s", statuses).isEqualTo(SIMULTANEOUS - 1)
        assertThat(weddings.findAll()).hasSize(1)
        assertThat(memberships.findAll()).hasSize(1)
    }

    @Test
    fun `a date in the past is accepted`() {
        // Deliberate, and stated as a test so it is a decision rather than an
        // omission: a couple building the ledger after the wedding is a real case,
        // and no record makes a past date invalid. The wrong-date cost is an edit in
        // 설정; the refusal cost is a couple that cannot start at all.
        val response = create(login(), body(date = "2019-05-05"))

        assertThat(response.statusCode()).isEqualTo(201)
        assertThat(response.json()["weddingDate"].asText()).isEqualTo("2019-05-05")
    }

    @Test
    fun `the names are stored trimmed`() {
        // To this schema `' 김신랑'` and `'김신랑'` are two different names, and every
        // later screen and the import matcher read what was stored.
        val response = create(login(), body(groom = "  김신랑 ", bride = "이신부  "))

        assertThat(response.json()["groomName"].asText()).isEqualTo("김신랑")
        assertThat(weddings.findAll().single().brideName).isEqualTo("이신부")
    }

    private fun create(
        cookies: List<HttpCookie>,
        body: String,
    ): HttpResponse<String> = post("/weddings", cookies, body)

    private fun create(
        session: HttpCookie,
        body: String,
    ): HttpResponse<String> = create(listOf(session), body)

    private fun body(
        date: String = "2026-10-10",
        groom: String = "김신랑",
        bride: String = "이신부",
        extra: String = "",
    ): String = """{"weddingDate":"$date","groomName":"$groom","brideName":"$bride"$extra}"""

    private fun headcountOf(weddingId: Long): Int? =
        jdbc.queryForObject("select guaranteed_headcount from wedding where id = ?", Int::class.javaObjectType, weddingId)

    private companion object {
        /** Kept under Hikari's default pool size: each attempt holds a connection while it waits. */
        private const val SIMULTANEOUS = 6
    }
}
