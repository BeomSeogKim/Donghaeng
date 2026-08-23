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
 * THE RED GATE OF `#123`, re-cut for `#166`: a logged-in person creates a wedding and
 * comes away with a wedding, **both of its seats**, and the free term it is born
 * holding.
 *
 * The rows beside the wedding are what this stop is about. Every later request
 * resolves `user → seat → wedding` (`#5`), so a wedding written without the caller's
 * seat is a ledger nobody can open — and the onboarding screen creates a wedding once,
 * so there is no second attempt that would fix it. The PARTNER's seat is written for a
 * different reason: `#9`'s invite must fill an identified row rather than create one
 * (notes/2026-08-22-decision-the-couples-two-seats.md §2), and "every wedding has
 * exactly two seats" has no lower half unless this transaction supplies it.
 *
 * **The request describes the caller and never their partner** (changed 2026-08-22).
 * `groomName` and `brideName` are gone; `side` and `name` replace them, which is the
 * same three members and a different question.
 *
 * Driven over real HTTP against a real Postgres carrying `V1`–`V3`, with a session
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

    @Autowired private lateinit var seats: WeddingSeatRepository

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
        // SQL, not `deleteAll()`: the entities carry `@SQLRestriction`, so a
        // repository delete cannot see the soft-deleted rows these tests make — and
        // a child row left behind fails the `wedding` delete on its foreign key.
        // FK order, subscription and seats before the wedding they point at.
        jdbc.update("delete from wedding_subscription")
        jdbc.update("delete from wedding_party")
        jdbc.update("delete from wedding")
    }

    @Test
    fun `a logged-in person creates a wedding, and the two seats that reach it`() {
        val session = login()
        val userId = callerId(session)

        val response = create(session, body())

        assertThat(response.statusCode()).isEqualTo(201)
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json")

        // The response is the created resource, with the id the client did not have.
        val created = response.json()
        assertThat(created["weddingDate"].asText()).isEqualTo("2026-10-10")
        assertThat(created["id"].asLong()).isPositive()

        // **Two seats, 신랑 먼저, and the partner's is empty rather than absent.** This
        // is the shape `web/` renders the ledger header from now that the wedding
        // carries no names of its own.
        val published = created["seats"]
        assertThat(published.map { it["side"].asText() }).containsExactly("GROOM", "BRIDE")
        assertThat(published[0]["name"].asText()).isEqualTo("김신랑")
        assertThat(published[1]["name"].isNull).isTrue()

        // The half a reviewer cannot see from the response, asserted from the
        // database: which seat carries the account.
        val wedding = weddings.findAll().single()
        assertThat(wedding.id).isEqualTo(created["id"].asLong())
        assertThat(wedding.createdBy).isEqualTo(userId)

        val mine = seats.findAll().single { it.side == WeddingSide.GROOM }
        assertThat(mine.weddingId).isEqualTo(wedding.id)
        assertThat(mine.userId).isEqualTo(userId)
        assertThat(mine.joinedAt).isNotNull()

        // The waiting seat: a side, and nothing that would be somebody else's to
        // type. `#9` fills it in place.
        val waiting = seats.findAll().single { it.side == WeddingSide.BRIDE }
        assertThat(waiting.weddingId).isEqualTo(wedding.id)
        assertThat(waiting.userId).isNull()
        assertThat(waiting.name).isNull()
        assertThat(waiting.joinedAt).isNull()
    }

    @Test
    fun `a wedding is born holding one live FREE term`() {
        // Not a payment feature — the row is what makes the FIRST payment a handover
        // instead of an insert, which is the trap `#168` would otherwise meet on day
        // one (notes/2026-08-22-decision-entitlement-belongs-to-the-wedding.md §4).
        // Written in the same transaction as the wedding, so it is asserted from the
        // endpoint that writes it rather than from a service test.
        val weddingId = create(login(), body()).json()["id"].asLong()

        val term =
            jdbc.queryForMap(
                "select plan, status, payer_id, started_at, ended_at from wedding_subscription where wedding_id = ?",
                weddingId,
            )

        assertThat(term["plan"]).isEqualTo("FREE")
        assertThat(term["status"]).isEqualTo("ACTIVE")
        // 결제는 사람의 행위, 권리는 웨딩의 상태 — nobody has paid, so no payer.
        assertThat(term["payer_id"]).isNull()
        assertThat(term["started_at"]).isNotNull()
        assertThat(term["ended_at"]).describedAs("null ended_at IS the live term").isNull()
    }

    @Test
    fun `the caller may take the 신부 seat, and then it is 신랑 that waits`() {
        // The reversal `#166` is about, at its narrowest: the request names the
        // CALLER's seat. A create that always filled 신랑 would pass every other test
        // in this class.
        val response = create(login(), body(side = "BRIDE", name = "이신부"))

        assertThat(response.statusCode()).isEqualTo(201)
        val published = response.json()["seats"]
        assertThat(published.map { it["side"].asText() }).containsExactly("GROOM", "BRIDE")
        assertThat(published[0]["name"].isNull).isTrue()
        assertThat(published[1]["name"].asText()).isEqualTo("이신부")

        assertThat(seats.findAll().single { it.userId != null }.side).isEqualTo(WeddingSide.BRIDE)
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
        val response = create(cookies = emptyList(), body = """{"name":""}""")

        assertThat(response.statusCode()).isEqualTo(401)
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json")
        assertThat(response.json()["code"].asText()).isEqualTo("UNAUTHENTICATED")
        assertThat(weddings.findAll()).isEmpty()
        assertThat(seats.findAll()).isEmpty()
    }

    @Test
    fun `a name that is blank, whitespace, or too long for its column is 400`() {
        val session = login()

        // `varchar(100)`, and the column is not the validator: unvalidated, the 101st
        // character is refused by Postgres, i.e. as a masked 500
        // (notes/2026-08-17-decision-log-masking-mechanism.md).
        val tooLong = "가".repeat(101)
        // **보이지 않는 문자로만 된 이름은 이름으로 치지 않는다** — the founder's rule
        // (`#187`), so this list is not "whitespace" but every way a name can carry no
        // visible character. Each entry is a class the previous spellings missed:
        //   U+3000 전각 공백 — an ordinary key on a Korean IME. It passed `@NotBlank`
        //     (Java trims `c <= ' '` only), was emptied by the service's Kotlin trim,
        //     and was stored as `''`.
        //   U+0000, U+0007 — the mirror image. A Kotlin-only trim leaves them intact and
        //     PgJDBC refuses NUL in a text parameter: a masked 500, not the 400 it is.
        //   U+200B, U+FEFF, U+00AD — stripped by NO trim in either language. These are
        //     why the rule asks "is there a visible character" rather than "does it trim
        //     to empty".        //
        // **U+0000 and U+0007 are sent as JSON ESCAPES, and that is not cosmetic.** A raw
        // control character in a JSON string is invalid JSON (RFC 8259), so the parser
        // refuses it as `MALFORMED_REQUEST_BODY` and the validator never runs. The escape
        // is what actually parses to a one-NUL name and reaches the rule.
        //
        // This endpoint is covered without its handler being touched, because all three
        // requests wear the one `@SeatName`.
        val rejected =
            listOf(
                body(name = ""),
                body(name = "   "),
                body(name = "\u3000"),
                body(name = "\\u0000"),
                body(name = "\\u0007"),
                body(name = "\u200b"),
                body(name = "\ufeff\u00ad"),
                body(name = tooLong),
            )

        rejected.forEach { body ->
            val response = create(session, body)
            assertThat(response.statusCode()).describedAs(body).isEqualTo(400)
            assertThat(response.json()["code"].asText()).describedAs(body).isEqualTo("VALIDATION_FAILED")
        }

        // A name one character shorter is a real name and is accepted, so the bound
        // above is the column's and not an off-by-one of its own.
        assertThat(create(session, body(name = "가".repeat(100))).statusCode()).isEqualTo(201)

        assertThat(weddings.findAll()).hasSize(1)
    }

    @Test
    fun `the length limit is measured on what was sent, before the trim`() {
        // Stated as a test because it is a choice, and `docs/api-spec.md` says the
        // same thing to `web/`. Trimming first would need every string field on
        // fifteen DTOs to remember a custom deserialiser — a rule that fails silently
        // when forgotten — to buy a case that only bites at exactly 101 characters
        // whose last one is a space.
        val response = create(login(), body(name = "가".repeat(100) + " "))

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
    fun `an omitted or unknown member is 400 too, and says the body could not be read`() {
        // A different `code` from the case above, and the spec says so rather than
        // this being smoothed over: the members are non-null Kotlin types, so an
        // omitted one fails while the body is being deserialised. `side` joins them
        // as of 2026-08-22 — and a side that is not one of the two words fails in the
        // same place, because there is no third seat to put it in.
        val session = login()

        // A `name` sent as an explicit `null` is here beside the omissions: Jackson takes
        // the same path for both on a non-null constructor parameter, and
        // `SeatNameValidator` returns true on null by the Jakarta convention — so Jackson
        // is the ONLY thing between a null and `request.name.trim()`, and
        // `docs/api-spec.md` publishes the refusal.
        listOf(
            """{"side":"GROOM","name":"김신랑"}""",
            """{"weddingDate":"2026-10-10","name":"김신랑"}""",
            """{"weddingDate":"2026-10-10","side":"BEST_MAN","name":"김신랑"}""",
            """{"weddingDate":"2026-10-10","side":"GROOM","name":null}""",
        ).forEach { body ->
            val response = create(session, body)
            assertThat(response.statusCode()).describedAs(body).isEqualTo(400)
            assertThat(response.json()["code"].asText()).describedAs(body).isEqualTo("MALFORMED_REQUEST_BODY")
        }

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
        assertThat(seats.findAll()).hasSize(2)
        assertThat(jdbc.queryForObject("select count(*) from wedding_subscription", Long::class.java)).isOne()
    }

    @Test
    fun `a person whose only seat was released creates again`() {
        // The case a bare `exists` query gets wrong. Every delete here is soft
        // (notes/2026-08-10-decision-soft-delete.md), so a person whose seat was
        // released still has a row — and if that row counted, they could never have a
        // ledger again.
        val session = login()
        val abandoned = create(session, body()).json()["id"].asLong()
        jdbc.update("update wedding_party set deleted_at = now() where wedding_id = ?", abandoned)

        val second = create(session, body(date = "2027-03-03"))

        assertThat(second.statusCode()).isEqualTo(201)
        assertThat(second.json()["id"].asLong()).isNotEqualTo(abandoned)
    }

    @Test
    fun `simultaneous creates leave exactly one wedding`() {
        // Two tabs, or one button double-tapped on a slow connection. A read-then-
        // insert inside the service is a race with a real window: every request
        // finds no seat, and every request creates one — after which the person's
        // ledger is whichever wedding `GET /weddings` happens to sort first. Driven
        // over HTTP because the window is between transactions.
        val session = login()
        val ready = CyclicBarrier(SIMULTANEOUS)
        val pool = Executors.newFixedThreadPool(SIMULTANEOUS)

        val statuses =
            try {
                (1..SIMULTANEOUS)
                    .map { attempt ->
                        pool.submit<Int> {
                            ready.await(10, TimeUnit.SECONDS)
                            create(session, body(name = "김신랑$attempt")).statusCode()
                        }
                    }.map { it.get(30, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }

        assertThat(statuses.count { it == 201 }).describedAs("%s", statuses).isEqualTo(1)
        assertThat(statuses.count { it == 409 }).describedAs("%s", statuses).isEqualTo(SIMULTANEOUS - 1)
        assertThat(weddings.findAll()).hasSize(1)
        assertThat(seats.findAll()).hasSize(2)
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
    fun `결혼식 이름 is optional, and a wedding created without one simply has none`() {
        // The ordinary case, and the reason the member is nullable AND defaulted:
        // omitting it and sending `null` are the same request. A wedding with no name is
        // not an error state — 설정 adds one later
        // (notes/2026-08-23-decision-the-wedding-has-a-name.md).
        val session = login()

        val omitted = create(session, body()).json()
        assertThat(omitted["weddingName"].isNull).isTrue()

        clean()
        val explicitNull = create(session, body(extra = ",\"weddingName\":null")).json()
        assertThat(explicitNull["weddingName"].isNull).isTrue()
    }

    @Test
    fun `결혼식 이름 is stored trimmed, and comes back as stored`() {
        // The wedding's own name follows the seat name's write rule, at the same one
        // write point: to this schema `' 범석 희주의 가을'` and `'범석 희주의 가을'` are two
        // different names.
        val response = create(login(), body(extra = ",\"weddingName\":\"  범석 희주의 가을 \""))

        assertThat(response.statusCode()).isEqualTo(201)
        assertThat(response.json()["weddingName"].asText()).isEqualTo("범석 희주의 가을")
        assertThat(weddings.findAll().single().name).isEqualTo("범석 희주의 가을")
    }

    @Test
    fun `a 결혼식 이름 with no visible character is refused, and so is an over-long one`() {
        // **The same rule as the seat's name, out of the same predicate**
        // ([VisibleCharacters]) — a wedding named with one zero-width character is a
        // header rendering nothing, which is the identical failure `#187` found on
        // `wedding_party.name`. `@WeddingName` is a SECOND annotation only because `#8`'s
        // PATCH carries this member inside a `Patch`, which a composed `@Size` cannot
        // read; the rule itself has one implementation.
        val session = login()
        val rejected = listOf("", "   ", "　", "\\u0000", "​", "가".repeat(101))

        rejected.forEach { name ->
            val response = create(session, body(extra = ",\"weddingName\":\"$name\""))
            assertThat(response.statusCode()).describedAs(name).isEqualTo(400)
            assertThat(response.json()["code"].asText()).describedAs(name).isEqualTo("VALIDATION_FAILED")
        }

        // One character shorter is a real name, so the bound above is the column's.
        assertThat(create(session, body(extra = ",\"weddingName\":\"${"가".repeat(100)}\"")).statusCode()).isEqualTo(201)
    }

    @Test
    fun `the name is stored trimmed`() {
        // To this schema `' 김신랑'` and `'김신랑'` are two different names, and every
        // later screen and the import matcher read what was stored.
        val response = create(login(), body(name = "  김신랑 "))

        assertThat(response.json()["seats"][0]["name"].asText()).isEqualTo("김신랑")
        assertThat(seats.findAll().single { it.userId != null }.name).isEqualTo("김신랑")
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
        side: String = "GROOM",
        name: String = "김신랑",
        extra: String = "",
    ): String = """{"weddingDate":"$date","side":"$side","name":"$name"$extra}"""

    private fun headcountOf(weddingId: Long): Int? =
        jdbc.queryForObject("select guaranteed_headcount from wedding where id = ?", Int::class.javaObjectType, weddingId)

    private companion object {
        /** Kept under Hikari's default pool size: each attempt holds a connection while it waits. */
        private const val SIMULTANEOUS = 6
    }
}
