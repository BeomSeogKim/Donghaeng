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

/**
 * `POST /weddings/join/preview` (`#214`) — **the one thing this API tells somebody
 * about a wedding they are not in**, so what it does NOT say is asserted as hard as
 * what it does.
 *
 * The founder asked for it from the other side of the same field: 초대 수락 화면에
 * 결혼식 이름, 예식일, 그리고 신랑 혹은 신부의 이름
 * (notes/2026-08-23-decision-the-wedding-has-a-name.md). What makes it safe is the
 * token — holding one already entitles the holder to take the seat, so naming the
 * wedding it opens tells them strictly less than accepting would — and the assertions
 * below are about keeping that true: no wedding id in the answer, and every unusable
 * token answering exactly what an unknown one answers.
 *
 * Driven over real HTTP with a real invite, minted through `POST .../invite`: a test
 * that inserted a token row would not exercise the selector/verifier split the whole
 * refusal design rests on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Import(StubGoogleRegistration::class)
internal class PreviewInviteContractTest : ApiFixture() {
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
    fun `the invited person is shown 결혼식 이름, 예식일 and who invited them`() {
        val creator = login()
        val weddingId =
            post(
                "/weddings",
                listOf(creator),
                "{\"weddingDate\":\"2026-10-10\",\"side\":\"GROOM\",\"name\":\"김신랑\",\"weddingName\":\"범석 희주의 가을\"}",
            ).json()["id"]
                .asLong()
        val token = tokenFor(weddingId)

        val response = preview(loginAs("the-partner"), token)

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json")
        val preview = response.json()
        assertThat(preview["weddingName"].asText()).isEqualTo("범석 희주의 가을")
        assertThat(preview["weddingDate"].asText()).isEqualTo("2026-10-10")
        // 초대한 사람 is the seat that is already taken, with its 측 so the screen can
        // say 신랑 or 신부 — never the empty seat this token fills.
        assertThat(preview["invitedBy"]["side"].asText()).isEqualTo("GROOM")
        assertThat(preview["invitedBy"]["name"].asText()).isEqualTo("김신랑")

        // **No wedding id, and this is the assertion that keeps the exemption honest.**
        // Nothing this endpoint answers can be carried to a wedding-scoped endpoint, so
        // a token holder learns whose wedding it is and not which one to ask about.
        assertThat(preview.fieldNames().asSequence().toList())
            .containsExactlyInAnyOrder("weddingName", "weddingDate", "invitedBy")
    }

    @Test
    fun `a wedding with no name previews with none, rather than with something invented`() {
        // The ordinary case for a couple who created in a hurry. `null` is the answer;
        // what the accept screen renders in its place is `web/`'s decision, as it is
        // for an empty seat.
        val weddingId = createWedding(login())

        val preview = preview(loginAs("the-partner"), tokenFor(weddingId)).json()

        assertThat(preview["weddingName"].isNull).isTrue()
        assertThat(preview["invitedBy"]["name"].asText()).isEqualTo("김신랑")
    }

    @Test
    fun `previewing spends nothing — the same token still joins afterwards`() {
        // The whole point of a read: a person may open the link, see whose wedding it
        // is, and decide. A preview that consumed the token would turn "looking" into
        // the irreversible act the record is worried about.
        val weddingId = createWedding(login())
        val token = tokenFor(weddingId)
        val partner = loginAs("the-partner")

        assertThat(preview(partner, token).statusCode()).isEqualTo(200)
        assertThat(preview(partner, token).statusCode()).isEqualTo(200)

        val joined = post("/weddings/join", listOf(partner), "{\"token\":\"$token\",\"name\":\"이신부\"}")
        assertThat(joined.statusCode()).isEqualTo(200)
        assertThat(jdbc.queryForMap("select * from wedding_invite")["accepted_at"]).isNotNull()
    }

    @Test
    fun `every token this endpoint cannot use answers exactly what an unknown one answers`() {
        // The refusal design the accept endpoint already carries, asserted here as an
        // EQUALITY of documents rather than as three status codes: a guesser who could
        // tell "this selector exists, only the verifier is wrong" from "no such token"
        // would have turned the public half of the token into a target worth grinding.
        val weddingId = createWedding(login())
        val token = tokenFor(weddingId)
        val partner = loginAs("the-partner")

        val unknown = preview(partner, "not-a-token-at-all")
        assertThat(unknown.statusCode()).isEqualTo(404)
        assertThat(unknown.json()["code"].asText()).isEqualTo("INVITE_NOT_FOUND")

        // Ours, with the verifier changed: the selector is a public handle, and this is
        // the only thing between a guessed one and a stranger's ledger.
        val tampered = preview(partner, token.dropLast(1) + if (token.last() == 'A') 'B' else 'A')
        assertThat(tampered.statusCode()).isEqualTo(404)
        assertThat(withoutInstance(tampered)).isEqualTo(withoutInstance(unknown))

        // Spent, which stays inside the one answer: that a link was used is a fact
        // about the person who used it, and the one asking is somebody else.
        post("/weddings/join", listOf(partner), "{\"token\":\"$token\",\"name\":\"이신부\"}")
        val spent = preview(loginAs("a-third-person"), token)
        assertThat(spent.statusCode()).isEqualTo(404)
        assertThat(withoutInstance(spent)).isEqualTo(withoutInstance(unknown))
    }

    @Test
    fun `a stale link and a replaced one are told apart here exactly as they are on the accept`() {
        // Both are said only AFTER the presented verifier matched, which is the whole
        // of why saying them is safe — and the recovery is worth saying: 파트너에게 새
        // 링크를 요청하세요 (notes/2026-08-22-decision-the-superseded-link-speaks.md).
        // A preview that hid these behind INVITE_NOT_FOUND would send the holder to a
        // dead end the accept screen could have explained.
        val weddingId = createWedding(loginAs("the-creator"))
        val stale = tokenFor(weddingId)
        // `ck_wedding_invite_term` demands expires_at > issued_at, so both move.
        jdbc.update("update wedding_invite set issued_at = now() - interval '2 days', expires_at = now() - interval '1 day'")

        val expired = preview(loginAs("the-partner"), stale)
        assertThat(expired.statusCode()).isEqualTo(404)
        assertThat(expired.json()["code"].asText()).isEqualTo("INVITE_EXPIRED")

        // A second wedding and two fresh people: 한 사람은 웨딩 하나 is checked first, so
        // reusing either of the two above would answer 409 and prove nothing.
        clean()
        val second = createWedding(loginAs("the-other-creator"))
        val superseded = tokenFor(second)
        // 발급 IS 재발급: minting a second link kills the first.
        tokenFor(second)

        val replaced = preview(loginAs("the-other-partner"), superseded)
        assertThat(replaced.statusCode()).isEqualTo(404)
        assertThat(replaced.json()["code"].asText()).isEqualTo("INVITE_SUPERSEDED")
    }

    @Test
    fun `somebody who already has a wedding is told so, before the token is looked at`() {
        // The accept endpoint's first refusal, in the same order and with the same
        // code, so that the screen states the outcome the tap would have had. It is
        // also what stops a preview from naming a wedding to somebody who could never
        // join it.
        val weddingId = createWedding(login())
        val token = tokenFor(weddingId)
        val outsider = loginAs("someone-else")
        createWedding(outsider)

        val refused = preview(outsider, token)

        assertThat(refused.statusCode()).isEqualTo(409)
        assertThat(refused.json()["code"].asText()).isEqualTo("ALREADY_IN_A_WEDDING")
        // And the same request with a token nobody ever issued is the SAME answer, so
        // the refusal cannot be used to ask whether a token is live.
        assertThat(withoutInstance(preview(outsider, "not-a-token-at-all"))).isEqualTo(withoutInstance(refused))
    }

    @Test
    fun `a seat somebody has already taken is a 409, not a wedding named to a stranger`() {
        // Reachable only when the seat is filled while a live invite still stands —
        // the fixture's SQL claim does exactly that, and so does any path that fills a
        // seat without spending the token.
        val weddingId = createWedding(login())
        val token = tokenFor(weddingId)
        joinAsPartner(weddingId)

        val refused = preview(loginAs("a-third-person"), token)

        assertThat(refused.statusCode()).isEqualTo(409)
        assertThat(refused.json()["code"].asText()).isEqualTo("PARTNER_ALREADY_JOINED")
    }

    @Test
    fun `an anonymous request is refused before the body is read`() {
        // Under `permitAll` the session is the only thing in front of a scopeless
        // endpoint, and this one answers with somebody else's wedding. A 401 rather
        // than a 404 means an anonymous caller learns nothing about the token it sent.
        val weddingId = createWedding(login())
        val token = tokenFor(weddingId)

        val response = post("/weddings/join/preview", emptyList(), "{\"token\":\"$token\"}")

        assertThat(response.statusCode()).isEqualTo(401)
        assertThat(response.json()["code"].asText()).isEqualTo("UNAUTHENTICATED")
    }

    private fun preview(
        session: HttpCookie,
        token: String,
    ): HttpResponse<String> = post("/weddings/join/preview", listOf(session), "{\"token\":\"$token\"}")

    /** The live token for this wedding's empty seat, minted by whoever created it. */
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
}
