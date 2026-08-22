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
import java.time.Duration
import java.time.Instant

/**
 * HALF THE RED GATE OF `#181`: a person who holds a seat mints the link that fills the
 * other one — and can mint a second one, which kills the first.
 *
 * The accept half is `AcceptInviteContractTest`. They are split because they are two
 * endpoints with two different scopes: this one is wedding-scoped in the ordinary way,
 * and the one that consumes the token cannot be, since the caller is not a member yet.
 *
 * **What is asserted here that no response can show**: the token is stored as
 * `selector` plus a SHA-256 of its verifier, so the row this endpoint writes cannot be
 * turned back into a working link. Whoever holds a live token enters the ledger and
 * reads every 하객's contact (notes/2026-08-22-decision-the-invite-link.md), which is
 * what makes a database dump the threat worth a test rather than a comment.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Import(StubGoogleRegistration::class)
internal class IssueInviteContractTest : ApiFixture() {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @BeforeEach
    @AfterEach
    fun clean() {
        // FK order: the invite points at a seat, the seat and the term at a wedding.
        jdbc.update("delete from wedding_invite")
        jdbc.update("delete from wedding_subscription")
        jdbc.update("delete from wedding_party")
        jdbc.update("delete from wedding")
    }

    @Test
    fun `a seat-holder mints a link for the seat nobody has taken`() {
        val session = login()
        val userId = callerId(session)
        val weddingId = createWedding(session)

        val response = issue(session, weddingId)

        assertThat(response.statusCode()).isEqualTo(201)
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json")

        // Two members and no more. The URL is the frontend's to build — it knows its
        // own origin and the API does not, and the token belongs in the FRAGMENT,
        // which is the one part of a URL that never reaches a server (§2 of the
        // record). An API that returned a link would be an API deciding that.
        val issued = response.json()
        assertThat(issued.fieldNames().asSequence().toList()).containsExactlyInAnyOrder("token", "expiresAt")
        assertThat(issued["token"].asText()).contains(".")

        // 최대 1일, the founder's call. Asserted as a window rather than an instant
        // because the server stamps its own `now`.
        val expiresAt = Instant.parse(issued["expiresAt"].asText())
        assertThat(Duration.between(Instant.now(), expiresAt)).isBetween(Duration.ofHours(23), Duration.ofDays(1))

        val row = jdbc.queryForMap("select * from wedding_invite")
        assertThat(row["seat_id"]).isEqualTo(waitingSeatOf(weddingId))
        assertThat(row["issued_by"]).isEqualTo(userId)
        assertThat(row["accepted_at"]).isNull()
        assertThat(row["revoked_at"]).isNull()
    }

    @Test
    fun `the row cannot be turned back into the link`() {
        val session = login()
        val token = issue(session, createWedding(session)).json()["token"].asText()
        val (selector, verifier) = token.split(".", limit = 2)

        val row = jdbc.queryForMap("select selector, verifier_hash from wedding_invite")

        // The selector is a public handle and is stored as it travels; the verifier
        // is the half that grants anything and is never stored, in any form.
        assertThat(row["selector"]).isEqualTo(selector)
        assertThat(row["verifier_hash"]).isNotEqualTo(verifier)
        assertThat(row["verifier_hash"] as String).hasSize(64)

        // The whole row, read as one string: a dump of this table hands over no
        // working invite link, which is the property that makes a one-day bearer
        // credential affordable at all.
        val whole = jdbc.queryForMap("select * from wedding_invite").values.joinToString()
        assertThat(whole).doesNotContain(verifier).doesNotContain(token)
    }

    @Test
    fun `재발급 kills the previous token, and leaves one live invite`() {
        // Without this a couple who taps 재발급 three times holds three live
        // credentials in three places, and the one-day life the founder chose is
        // undone by holding several at once (2026-08-22 §1). The revoked row STAYS —
        // it is testimony that a credential existed, not a mistake to erase.
        val session = login()
        val weddingId = createWedding(session)

        val first = issue(session, weddingId).json()["token"].asText()
        val second = issue(session, weddingId)

        assertThat(second.statusCode()).isEqualTo(201)
        assertThat(second.json()["token"].asText()).isNotEqualTo(first)
        assertThat(jdbc.queryForObject("select count(*) from wedding_invite", Long::class.java)).isEqualTo(2)
        assertThat(
            jdbc.queryForObject(
                "select count(*) from wedding_invite where accepted_at is null and revoked_at is null",
                Long::class.java,
            ),
        ).isOne()
        assertThat(jdbc.queryForObject("select count(*) from wedding_invite where revoked_at is not null", Long::class.java)).isOne()
    }

    @Test
    fun `a wedding whose seats are both taken has nothing to invite`() {
        val session = login()
        val weddingId = createWedding(session)
        joinAsPartner(weddingId)

        val response = issue(session, weddingId)

        // 409 and not 404: this says nothing about any wedding the caller may not
        // have — it is a fact about the ledger they are already inside, and the
        // recovery is to stop asking rather than to try another id.
        assertThat(response.statusCode()).isEqualTo(409)
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json")
        assertThat(response.json()["code"].asText()).isEqualTo("PARTNER_ALREADY_JOINED")
        assertThat(jdbc.queryForObject("select count(*) from wedding_invite", Long::class.java)).isZero()
    }

    @Test
    fun `a logged-in stranger is 404, and cannot tell the wedding from one that never existed`() {
        val session = login()
        val weddingId = createWedding(session)
        val stranger = loginAs("a-stranger")

        val refused = issue(stranger, weddingId)
        val nonexistent = issue(stranger, weddingId + 9_999)

        assertThat(refused.statusCode()).isEqualTo(404)
        assertThat(refused.json()["code"].asText()).isEqualTo("WEDDING_NOT_FOUND")
        // Identical in every member but `instance`, which is the only one two
        // different ids may legitimately differ in — otherwise the pair is a
        // wedding-id oracle (notes/2026-08-10-decision-cross-tenant-status-code.md).
        assertThat(withoutInstance(refused)).isEqualTo(withoutInstance(nonexistent))
        assertThat(jdbc.queryForObject("select count(*) from wedding_invite", Long::class.java)).isZero()
    }

    @Test
    fun `an anonymous request is 401, whatever id it names`() {
        val weddingId = createWedding(login())

        val response = post("/weddings/$weddingId/invite", emptyList())

        assertThat(response.statusCode()).isEqualTo(401)
        assertThat(response.json()["code"].asText()).isEqualTo("UNAUTHENTICATED")
        assertThat(jdbc.queryForObject("select count(*) from wedding_invite", Long::class.java)).isZero()
    }

    @Test
    fun `the link is for whichever seat is waiting, not for 신부 by construction`() {
        // The couple who signed up 신부 먼저. Their empty seat is 신랑, and an invite
        // that assumed the other one would mint a token for a seat that is already
        // taken — which every test above would still pass, since they all create the
        // wedding the other way round.
        val session = login()
        val weddingId =
            post(
                "/weddings",
                listOf(session),
                """{"weddingDate":"2026-10-10","side":"BRIDE","name":"이신부"}""",
            ).json()["id"]
                .asLong()

        val response = issue(session, weddingId)

        assertThat(response.statusCode()).isEqualTo(201)
        assertThat(jdbc.queryForObject("select side from wedding_invite i join wedding_party s on s.id = i.seat_id", String::class.java))
            .isEqualTo("GROOM")
    }

    private fun issue(
        session: HttpCookie,
        weddingId: Long,
    ): HttpResponse<String> = post("/weddings/$weddingId/invite", listOf(session))

    private fun waitingSeatOf(weddingId: Long): Long =
        jdbc.queryForObject(
            "select id from wedding_party where wedding_id = ? and user_id is null and deleted_at is null",
            Long::class.java,
            weddingId,
        )!!
}
