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
import java.time.Instant
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * THE OTHER HALF OF `#181`'s RED GATE: the partner arrives holding a token and comes
 * away holding the second seat.
 *
 * **`POST /weddings/join` is the third endpoint in the product that is not scoped to a
 * wedding**, and it is the only one that could not be: the caller holds no seat yet, so
 * `user → seat → wedding` has nothing to resolve. What stands in its place is the
 * token, and everything below is about what that substitution costs — a guessed token,
 * a replayed token, a stale token, and two people opening the same link.
 *
 * **Acceptance is an UPDATE of one identified row.** Both seats exist from the moment
 * the wedding does (notes/2026-08-22-decision-the-couples-two-seats.md §2), so the
 * failure to hold off is a lost update and not a duplicate seat — which is why the
 * simultaneous case below asserts the occupant rather than the row count.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Import(StubGoogleRegistration::class)
internal class AcceptInviteContractTest : ApiFixture() {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @BeforeEach
    @AfterEach
    fun clean() {
        jdbc.update("delete from wedding_invite")
        jdbc.update("delete from wedding_subscription")
        jdbc.update("delete from wedding_party")
        jdbc.update("delete from wedding")
    }

    @Test
    fun `the partner joins, writing their own name into their own seat`() {
        val weddingId = createWedding(login())
        val token = tokenFor(weddingId)
        val partner = loginAs("the-partner")
        val partnerId = callerId(partner)

        val response = join(partner, body(token))

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json")

        // The wedding itself, so the client that has just joined has the id every
        // scoped request needs and does not have to call `GET /weddings` to learn it.
        val wedding = response.json()
        assertThat(wedding["id"].asLong()).isEqualTo(weddingId)
        assertThat(wedding["seats"].map { it["side"].asText() }).containsExactly("GROOM", "BRIDE")
        assertThat(wedding["seats"][0]["name"].asText()).isEqualTo("김신랑")
        assertThat(wedding["seats"][1]["name"].asText()).isEqualTo("이신부")

        val seat = jdbc.queryForMap("select * from wedding_party where wedding_id = ? and side = 'BRIDE'", weddingId)
        assertThat(seat["user_id"]).isEqualTo(partnerId)
        assertThat(seat["name"]).isEqualTo("이신부")
        // `joined_at` is when the seat was CLAIMED and is not `created_at`: the seat
        // was created with the wedding, and this is the moment it acquired a person.
        assertThat(seat["joined_at"]).isNotNull()
        assertThat(seat["created_at"]).isNotEqualTo(seat["joined_at"])
    }

    @Test
    fun `accepting spends the token, and who spent it is on the row`() {
        val weddingId = createWedding(login())
        val token = tokenFor(weddingId)
        val partner = loginAs("the-partner")
        val partnerId = callerId(partner)

        join(partner, body(token))

        val invite = jdbc.queryForMap("select * from wedding_invite")
        assertThat(invite["accepted_at"]).isNotNull()
        assertThat(invite["accepted_by"]).isEqualTo(partnerId)
    }

    @Test
    fun `a spent token is spent for everybody`() {
        // Single use, and it answers a different failure from expiry: an opened link
        // being REPLAYED, rather than an unopened one going stale. Replayed by a third
        // person here, since the partner who already joined would be refused one step
        // earlier by 한 사람은 웨딩 하나 and would prove nothing about the token.
        val weddingId = createWedding(login())
        val token = tokenFor(weddingId)
        join(loginAs("the-partner"), body(token))

        val replay = join(loginAs("a-third-person"), body(token))

        // **Pinned, not a disjunction.** The first join has COMMITTED, so the token is
        // already spent when this one reads it and the refusal short-circuits before
        // the seat is ever looked at — there is nothing bimodal here, unlike the
        // simultaneous case below. `docs/api-spec.md` publishes exactly this code for a
        // spent token and `#182` branches on it, so a test that accepted either answer
        // was weaker than the contract it was meant to hold.
        assertThat(replay.statusCode()).isEqualTo(404)
        assertThat(replay.json()["code"].asText()).isEqualTo("INVITE_NOT_FOUND")
        assertThat(occupantsOf(weddingId)).isEqualTo(2)
    }

    @Test
    fun `a link that has gone stale says so, because a new one is one tap away`() {
        // The one refusal that is told apart from the others, and it is safe to tell:
        // it is only ever said to someone presenting a token that WAS ours, and a
        // guessed selector answers INVITE_NOT_FOUND exactly as garbage does. Saying it
        // is what makes 재발급 the recovery the founder's one-day call depends on
        // (notes/2026-08-22-decision-the-invite-link.md §1).
        val weddingId = createWedding(login())
        val token = tokenFor(weddingId)
        jdbc.update("update wedding_invite set issued_at = now() - interval '2 days', expires_at = now() - interval '1 day'")

        val response = join(loginAs("the-partner"), body(token))

        assertThat(response.statusCode()).isEqualTo(404)
        assertThat(response.json()["code"].asText()).isEqualTo("INVITE_EXPIRED")
        assertThat(occupantsOf(weddingId)).isOne()
    }

    @Test
    fun `a superseded token says a newer link exists, because that is where the recovery is`() {
        // What 재발급 leaves behind, and 발급 IS 재발급 — so with a one-day life this is
        // an ordinary daily state, not an edge case: the old link sits in one person's
        // KakaoTalk while the working one is on the other's phone
        // (notes/2026-08-22-decision-the-superseded-link-speaks.md). Told apart on the
        // same argument INVITE_EXPIRED stands on, unmodified: the check is reached only
        // after the presented verifier matched, so a guesser never gets here.
        val weddingId = createWedding(login())
        val stale = tokenFor(weddingId)
        tokenFor(weddingId)

        val response = join(loginAs("the-partner"), body(stale))

        assertThat(response.statusCode()).isEqualTo(404)
        assertThat(response.json()["code"].asText()).isEqualTo("INVITE_SUPERSEDED")
        assertThat(occupantsOf(weddingId)).isOne()

        // And the newest link still works — the whole point of saying which death it
        // was is that this is the link the person should be asking their partner for.
        assertThat(join(loginAs("the-partner"), body(tokenFor(weddingId))).statusCode()).isEqualTo(200)
    }

    @Test
    fun `a token that was spent and then superseded still says nothing about the person who spent it`() {
        // The split has ONE side. `accepted_at` is somebody else's business — telling a
        // second arrival "this link was already used" is a fact about the partner, not
        // about the token they hold — so it stays INVITE_NOT_FOUND even when a later
        // 재발급 has also stamped `revoked_at` on the same row. Asserted because the
        // obvious reading of the two columns ("revoked? then superseded") gets this
        // wrong, and the ordinary accept path leaves exactly this shape behind.
        val weddingId = createWedding(login())
        val spent = tokenFor(weddingId)
        join(loginAs("the-partner"), body(spent))
        jdbc.update("update wedding_invite set revoked_at = now() where accepted_at is not null")

        val replay = join(loginAs("a-third-person"), body(spent))

        assertThat(replay.statusCode()).isEqualTo(404)
        assertThat(replay.json()["code"].asText()).isEqualTo("INVITE_NOT_FOUND")
        assertThat(occupantsOf(weddingId)).isEqualTo(2)
    }

    @Test
    fun `a token whose seat was released, or whose wedding was deleted, opens nothing`() {
        // **Neither state is reachable through the API today** — there is no endpoint
        // that releases a seat or deletes a wedding — which is exactly why this is
        // written now: when one lands, nobody will remember that a live invite outlives
        // the ledger it points at. Both are published as INVITE_NOT_FOUND, and both are
        // one line of SQL away from being real.
        val released = createWedding(login())
        val releasedToken = tokenFor(released)
        jdbc.update("update wedding_party set deleted_at = now() where wedding_id = ? and user_id is null", released)

        val ofAReleasedSeat = join(loginAs("the-partner"), body(releasedToken))

        assertThat(ofAReleasedSeat.statusCode()).isEqualTo(404)
        assertThat(ofAReleasedSeat.json()["code"].asText()).isEqualTo("INVITE_NOT_FOUND")

        // The second condition, and it is not the first one restated: a soft-deleted
        // wedding KEEPS its seats — the partial indexes filter the seat's own
        // `deleted_at` only — so the seat below is live and the wedding is not.
        val deleted = createWedding(loginAs("another-couple"), name = "박신랑")
        val deletedToken = tokenFor(deleted)
        jdbc.update("update wedding set deleted_at = now() where id = ?", deleted)

        val ofADeletedWedding = join(loginAs("another-partner"), body(deletedToken))

        assertThat(ofADeletedWedding.statusCode()).isEqualTo(404)
        assertThat(ofADeletedWedding.json()["code"].asText()).isEqualTo("INVITE_NOT_FOUND")
        assertThat(occupantsOf(deleted)).isOne()
    }

    @Test
    fun `a guessed token is refused, and a right selector with a wrong verifier is refused identically`() {
        val weddingId = createWedding(login())
        val token = tokenFor(weddingId)
        val selector = token.substringBefore(".")
        val partner = loginAs("the-partner")

        val nonsense = join(partner, body("not-even-shaped-like-one"))
        val halfRight = join(partner, body("$selector.wrong-verifier"))
        // **The empty string, pinned because its answer is a decision.** `token` carries
        // no `@NotBlank` on purpose — every token this endpoint cannot use gets ONE
        // answer — and `docs/api-spec.md` says so in as many words. Adding the
        // annotation later would quietly turn this into a 400 `VALIDATION_FAILED` with
        // the suite green and the published contract wrong.
        val empty = join(partner, body(""))

        assertThat(nonsense.statusCode()).isEqualTo(404)
        assertThat(nonsense.json()["code"].asText()).isEqualTo("INVITE_NOT_FOUND")
        assertThat(empty.statusCode()).isEqualTo(404)
        assertThat(empty.json()["code"].asText()).isEqualTo("INVITE_NOT_FOUND")
        // The selector is a public handle carrying no authority: it identifies the
        // row, and the verifier is the only half that grants anything. Both answers
        // are identical in every member, so knowing a selector is worth nothing.
        assertThat(withoutInstance(halfRight)).isEqualTo(withoutInstance(nonsense))
        assertThat(occupantsOf(weddingId)).isOne()

        // And none of it burned the invite: a stranger guessing must not be able to
        // stop the real partner from joining.
        assertThat(join(partner, body(token)).statusCode()).isEqualTo(200)
    }

    @Test
    fun `the token never reaches a log or an error document`() {
        // The whole of `#69`'s complaint, held as a test: a token in a path is
        // recorded in the access log and reflected in `instance`. This one travels in
        // a body, and the error it produces says only which endpoint refused it.
        val response = join(loginAs("the-partner"), body("selector.verifier-that-is-not-ours"))

        assertThat(response.json()["instance"].asText()).isEqualTo("/weddings/join")
        assertThat(response.body()).doesNotContain("verifier-that-is-not-ours")

        // **THE LOG HALF, which this test's title promised and did not check** — found
        // by the `#186` security audit. `InviteToken` masks itself, and that masking
        // stops the moment the value is copied into a DTO: a `data class` generates a
        // `toString()` that prints every member, and Spring MVC logs exactly that on
        // BOTH legs at DEBUG — `Read "..." to [...]` inbound
        // (AbstractMessageConverterMethodArgumentResolver), `Writing [...]` outbound
        // (AbstractMessageConverterMethodProcessor). `LogFormatUtils` truncates at 100
        // characters and both renderings are shorter than that with the 66-character
        // token intact, so the whole live credential fits inside the window.
        //
        // Nothing else would catch it. `spring.mvc.log-request-details: false` does not
        // gate that path, and `LogLevelGuard` pins the Hibernate and pgjdbc loggers but
        // nothing under `org.springframework.web` — so one deploy-platform variable
        // writes working invite links to the log with the rest of this suite green.
        val token = "SEL3ct0r.a-live-verifier-nobody-may-read-in-a-log"
        assertThat(JoinWeddingRequest(token = token, name = "이신부").toString()).doesNotContain(token)
        assertThat(IssuedInviteResponse(token = token, expiresAt = Instant.now()).toString()).doesNotContain(token)
    }

    @Test
    fun `a caller who already belongs to a wedding is refused, and the token survives`() {
        // The same code, from the same check, as `POST /weddings` — one fact about the
        // caller's account, one word for it, one recovery
        // (notes/2026-08-21-decision-one-wedding-per-person.md §3).
        val weddingId = createWedding(login())
        val token = tokenFor(weddingId)
        val busy = loginAs("someone-with-their-own-wedding")
        createWedding(busy, name = "박신랑")

        val response = join(busy, body(token))

        assertThat(response.statusCode()).isEqualTo(409)
        assertThat(response.json()["code"].asText()).isEqualTo("ALREADY_IN_A_WEDDING")

        // Load-bearing: the check runs BEFORE the token is spent, so a person tapping
        // a link they cannot use does not destroy their partner's invite.
        assertThat(jdbc.queryForObject("select count(*) from wedding_invite where accepted_at is null", Long::class.java)).isOne()
        assertThat(join(loginAs("the-partner"), body(token)).statusCode()).isEqualTo(200)
    }

    @Test
    fun `an anonymous request is 401, and is refused before its body is read`() {
        // A body that is invalid on purpose: an anonymous caller gets one answer,
        // never one that depends on what they sent. It is also what keeps a token out
        // of the hands of a flow that has no session to attach it to.
        val response = join(emptyList(), """{"token":""}""")

        assertThat(response.statusCode()).isEqualTo(401)
        assertThat(response.json()["code"].asText()).isEqualTo("UNAUTHENTICATED")
    }

    @Test
    fun `a name that is blank, whitespace, or too long for its column is 400`() {
        val weddingId = createWedding(login())
        val token = tokenFor(weddingId)
        val partner = loginAs("the-partner")

        // **보이지 않는 문자로만 된 이름은 이름으로 치지 않는다** (`#187`) — the same rule
        // `POST /weddings` and `PUT .../seats/me` apply, because since `#187` it is
        // literally one `@SeatName` and not three matching copies. The witnesses are the
        // three classes the older spellings each missed; see `CreateWeddingContractTest`
        // for the full note.
        //
        // **A fresh token per case.** A refused name does not spend one, so reuse works
        // today — but under a regression the first ACCEPTED name spends the token and
        // every later case fails as a 404, pointing the failure at the wrong character.        //
        // **U+0000 and U+0007 are sent as JSON ESCAPES, and that is not cosmetic.** A raw
        // control character in a JSON string is invalid JSON (RFC 8259), so the parser
        // refuses it as `MALFORMED_REQUEST_BODY` and the validator never runs. The escape
        // is what actually parses to a one-NUL name and reaches the rule.
        listOf("", "   ", "\u3000", "\\u0000", "\\u0007", "\u200b", "\ufeff\u00ad", "가".repeat(101)).forEach { name ->
            val response = join(partner, body(tokenFor(weddingId), name))
            assertThat(response.statusCode()).describedAs(name).isEqualTo(400)
            assertThat(response.json()["code"].asText()).describedAs(name).isEqualTo("VALIDATION_FAILED")
        }

        // A refused name does not spend the token either — the person simply corrects
        // it and taps again.
        assertThat(join(partner, body(tokenFor(weddingId), "가".repeat(100))).statusCode()).isEqualTo(200)
    }

    @Test
    fun `an omitted member says the body could not be read`() {
        val weddingId = createWedding(login())
        val token = tokenFor(weddingId)
        val partner = loginAs("the-partner")

        // `null` sits beside the omissions deliberately: Jackson takes the same path for
        // both on a non-null constructor parameter, and `SeatNameValidator` returns true
        // on null by the Jakarta convention — so Jackson is the ONLY thing between a null
        // and `request.name.trim()`, and `docs/api-spec.md` publishes the refusal.
        listOf("""{"name":"이신부"}""", """{"token":"$token"}""", """{"token":"$token","name":null}""").forEach { body ->
            val response = join(partner, body)
            assertThat(response.statusCode()).describedAs(body).isEqualTo(400)
            assertThat(response.json()["code"].asText()).describedAs(body).isEqualTo("MALFORMED_REQUEST_BODY")
        }
    }

    @Test
    fun `the name is stored trimmed`() {
        val weddingId = createWedding(login())
        val token = tokenFor(weddingId)

        val response = join(loginAs("the-partner"), body(token, "  이신부 "))

        assertThat(response.json()["seats"][1]["name"].asText()).isEqualTo("이신부")
    }

    @Test
    fun `two people opening the same link leave one person in the seat`() {
        // The failure the two-seats design turns into a lost update: without the
        // seat's row lock both requests read an empty seat and both write their own
        // name into it, and the loser is silently signed into somebody's ledger while
        // the winner's name is the one on the row. Driven over HTTP because the window
        // is between transactions.
        val weddingId = createWedding(login())
        val token = tokenFor(weddingId)
        val arrivals = (1..SIMULTANEOUS).map { loginAs("arrival-$it") to "하객$it" }
        val ready = CyclicBarrier(SIMULTANEOUS)
        val pool = Executors.newFixedThreadPool(SIMULTANEOUS)

        val responses =
            try {
                arrivals
                    .map { (session, name) ->
                        pool.submit<HttpResponse<String>> {
                            ready.await(10, TimeUnit.SECONDS)
                            join(session, body(token, name))
                        }
                    }.map { it.get(30, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }

        val statuses = responses.map { it.statusCode() }
        assertThat(statuses.count { it == 200 }).describedAs("%s", statuses).isOne()
        assertThat(statuses.filter { it != 200 }).describedAs("%s", statuses).allMatch { it == 404 || it == 409 }
        assertThat(occupantsOf(weddingId)).isEqualTo(2)
        assertThat(jdbc.queryForObject("select count(*) from wedding_invite where accepted_at is not null", Long::class.java)).isOne()

        // The seat's name and its account belong to the SAME arrival — the property a
        // lost update destroys while leaving every count above correct.
        val seat = jdbc.queryForMap("select user_id, name from wedding_party where wedding_id = ? and side = 'BRIDE'", weddingId)
        val winner = responses.single { it.statusCode() == 200 }
        assertThat(seat["name"]).isEqualTo(winner.json()["seats"][1]["name"].asText())
        assertThat(seat["user_id"]).isEqualTo(callerId(arrivals.single { (_, name) -> name == seat["name"] }.first))
    }

    /** A live token for [weddingId]'s waiting seat, minted the way a couple mints one. */
    private fun tokenFor(weddingId: Long): String {
        val issuer =
            jdbc.queryForObject(
                "select user_id from wedding_party where wedding_id = ? and user_id is not null",
                Long::class.javaObjectType,
                weddingId,
            )
        return post("/weddings/$weddingId/invite", listOf(sessionOf(issuer!!)))
            .json()["token"]
            .asText()
    }

    /**
     * A session for a user id that already exists — the issuer, after the test has
     * logged in as somebody else. [ApiFixture.loginAs] keys on the provider subject
     * rather than on our id, so this asks the provider for the same subject again and
     * gets the same account.
     */
    private fun sessionOf(userId: Long): HttpCookie {
        val subject =
            jdbc.queryForObject(
                "select provider_user_id from oauth_identity where user_id = ?",
                String::class.java,
                userId,
            )
        return loginAs(subject!!)
    }

    private fun join(
        session: HttpCookie,
        body: String,
    ): HttpResponse<String> = join(listOf(session), body)

    private fun join(
        cookies: List<HttpCookie>,
        body: String,
    ): HttpResponse<String> = post("/weddings/join", cookies, body)

    private fun body(
        token: String,
        name: String = "이신부",
    ): String = """{"token":"$token","name":"$name"}"""

    private fun occupantsOf(weddingId: Long): Long =
        jdbc.queryForObject(
            "select count(*) from wedding_party where wedding_id = ? and user_id is not null and deleted_at is null",
            Long::class.java,
            weddingId,
        )!!

    private companion object {
        /** Kept under Hikari's default pool size: each arrival holds a connection while it waits. */
        const val SIMULTANEOUS = 4
    }
}
