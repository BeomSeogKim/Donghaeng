package com.donghaeng.guest

import com.donghaeng.auth.StubGoogleRegistration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.net.HttpCookie
import java.time.Instant

/**
 * `PATCH /weddings/{weddingId}` (`#173`, the backend half of `#8`) against
 * `docs/api-spec.md`.
 *
 * **This is the endpoint 보증인원 enters the product through**, so the assertions
 * below are the first ones in the suite where the venue's number is anything but
 * NULL — and the ones that matter most are about the two ways a member can be
 * missing from a body. 생략과 `null`은 다른 뜻이고
 * (notes/2026-08-22-decision-partial-update-shape.md), a test that only ever sends
 * both members would not notice if they stopped differing.
 *
 * It lives in `guest/` because the handler does, and the handler does because the
 * response carries the ledger's aggregate — [WeddingUpdateService] argues that. It
 * extends [GuestFixture] for a second reason too: the recomputed 인원수 is only
 * worth asserting over a ledger that has somebody in it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Import(StubGoogleRegistration::class)
internal class UpdateWeddingContractTest : GuestFixture() {
    @Test
    fun `보증인원 is set, and rides back inside the recomputed headcount`() {
        val session = login()
        val weddingId = createWedding(session)

        val response = patch("/weddings/$weddingId", listOf(session), """{"guaranteedHeadcount":150}""")

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.json()["headcount"]["guaranteedHeadcount"].asInt()).isEqualTo(150)
        // The couple must not have to refetch what they just typed.
        assertThat(headcount(session, weddingId)["guaranteedHeadcount"].asInt()).isEqualTo(150)
    }

    @Test
    fun `the number the mutation answers with is the ledger's, counted after the write`() {
        val session = login()
        val weddingId = createWedding(session)
        post("/weddings/$weddingId/guests", listOf(session), """{"name":"김영수","side":"GROOM","expectedPartySize":3}""")

        val response = patch("/weddings/$weddingId", listOf(session), """{"guaranteedHeadcount":150}""")

        val headcount = response.json()["headcount"]
        assertThat(headcount["mealHeadcount"].asInt()).isEqualTo(3)
        assertThat(headcount["guaranteedHeadcount"].asInt()).isEqualTo(150)
    }

    @Test
    fun `예식일 is changed, and the response is the wedding as it now stands`() {
        val session = login()
        val weddingId = createWedding(session)

        val response = patch("/weddings/$weddingId", listOf(session), """{"weddingDate":"2027-03-14"}""")

        assertThat(response.statusCode()).isEqualTo(200)
        val wedding = response.json()["wedding"]
        assertThat(wedding["id"].asLong()).isEqualTo(weddingId)
        assertThat(wedding["weddingDate"].asText()).isEqualTo("2027-03-14")
        assertThat(wedding["seats"].map { it["side"].asText() }).containsExactly("GROOM", "BRIDE")
        assertThat(get("/weddings/$weddingId", listOf(session)).json()["weddingDate"].asText()).isEqualTo("2027-03-14")
    }

    @Test
    fun `a member that is not sent is left alone`() {
        // **What this case does NOT hold**: two PATCHes are two transactions, so the
        // second reloads a row already holding 150 and writes 150 back — a handler
        // that assigned every column would pass here identically. That the UPDATE does
        // not NAME the member the request never mentioned is
        // `WeddingUpdateStatementTest`'s, against the issued SQL.
        val session = login()
        val weddingId = createWedding(session)
        patch("/weddings/$weddingId", listOf(session), """{"guaranteedHeadcount":150}""")

        val response = patch("/weddings/$weddingId", listOf(session), """{"weddingDate":"2027-03-14"}""")

        // The 예식일 request never mentioned 보증인원, so it did not write one.
        assertThat(response.json()["headcount"]["guaranteedHeadcount"].asInt()).isEqualTo(150)
        assertThat(response.json()["wedding"]["weddingDate"].asText()).isEqualTo("2027-03-14")
    }

    @Test
    fun `보증인원 sent as null goes back to not being set, and the member disappears`() {
        val session = login()
        val weddingId = createWedding(session)
        patch("/weddings/$weddingId", listOf(session), """{"guaranteedHeadcount":150}""")

        val response = patch("/weddings/$weddingId", listOf(session), """{"guaranteedHeadcount":null}""")

        assertThat(response.statusCode()).isEqualTo(200)
        // Absent, not null and not zero — 계약 전 커플과 같은 상태로 돌아간다.
        assertThat(response.json()["headcount"].has("guaranteedHeadcount")).isFalse()
        assertThat(headcount(session, weddingId).has("guaranteedHeadcount")).isFalse()
    }

    @Test
    fun `the wedding member never carries 보증인원 — one response spells one number one way`() {
        val session = login()
        val weddingId = createWedding(session)

        val response = patch("/weddings/$weddingId", listOf(session), """{"guaranteedHeadcount":150}""")

        assertThat(response.json()["wedding"].has("guaranteedHeadcount")).isFalse()
        assertThat(get("/weddings/$weddingId", listOf(session)).json().has("guaranteedHeadcount")).isFalse()
    }

    @Test
    fun `예식일 cannot be cleared`() {
        val session = login()
        val weddingId = createWedding(session)

        val response = patch("/weddings/$weddingId", listOf(session), """{"weddingDate":null}""")

        assertThat(response.statusCode()).isEqualTo(400)
        assertThat(response.json()["code"].asText()).isEqualTo("VALIDATION_FAILED")
        // Refused, so nothing else in the body was applied either.
        assertThat(get("/weddings/$weddingId", listOf(session)).json()["weddingDate"].asText()).isEqualTo("2026-10-10")
    }

    @Test
    fun `a 보증인원 the column would refuse is a 400, not a masked 500`() {
        val session = login()
        val weddingId = createWedding(session)

        listOf("0", "-1").forEach { value ->
            val response = patch("/weddings/$weddingId", listOf(session), """{"guaranteedHeadcount":$value}""")

            assertThat(response.statusCode()).describedAs("guaranteedHeadcount=%s", value).isEqualTo(400)
            assertThat(response.json()["code"].asText()).isEqualTo("VALIDATION_FAILED")
        }
        assertThat(headcount(session, weddingId).has("guaranteedHeadcount")).isFalse()
    }

    @Test
    fun `a 예식일 the column cannot store is a 400`() {
        val session = login()
        val weddingId = createWedding(session)

        val response = patch("/weddings/$weddingId", listOf(session), """{"weddingDate":"+5874898-01-01"}""")

        assertThat(response.statusCode()).isEqualTo(400)
        assertThat(response.json()["code"].asText()).isEqualTo("VALIDATION_FAILED")
    }

    @Test
    fun `a member of the wrong type is refused while the body is being read`() {
        val session = login()
        val weddingId = createWedding(session)

        // Both members go through the same wrapper, so both are asserted: what reads
        // them is one deserializer, and a failure inside it must still surface as the
        // ordinary body-parse refusal rather than as a masked 500.
        listOf("""{"guaranteedHeadcount":"백오십"}""", """{"weddingDate":"2026-13-40"}""").forEach { body ->
            val response = patch("/weddings/$weddingId", listOf(session), body)

            assertThat(response.statusCode()).describedAs("%s", body).isEqualTo(400)
            assertThat(response.json()["code"].asText()).isEqualTo("MALFORMED_REQUEST_BODY")
        }
        assertThat(get("/weddings/$weddingId", listOf(session)).json()["weddingDate"].asText()).isEqualTo("2026-10-10")
    }

    @Test
    fun `an empty string is not a second way to spell null, on either member`() {
        val session = login()
        val weddingId = createWedding(session)
        patch("/weddings/$weddingId", listOf(session), """{"guaranteedHeadcount":150}""")

        // A number input the couple blanked serialises to "" unless the client
        // special-cases it, and `""` is a value the caller sent — not a request to
        // clear. Jackson coerces a blank string to null for both of these types, so
        // without a refusal in the deserializer this reads as `Set(null)`: the date
        // becomes a masked 500, and 보증인원 is destroyed by a 200 nobody asked for.
        listOf("""{"guaranteedHeadcount":""}""", """{"weddingDate":""}""", """{"weddingDate":"  "}""").forEach { body ->
            val response = patch("/weddings/$weddingId", listOf(session), body)

            assertThat(response.statusCode()).describedAs("%s", body).isEqualTo(400)
            assertThat(response.json()["code"].asText()).describedAs("%s", body).isEqualTo("MALFORMED_REQUEST_BODY")
        }
        assertThat(headcount(session, weddingId)["guaranteedHeadcount"].asInt()).isEqualTo(150)
        assertThat(get("/weddings/$weddingId", listOf(session)).json()["weddingDate"].asText()).isEqualTo("2026-10-10")
    }

    @Test
    fun `an empty body changes nothing, and does not claim the row was touched`() {
        val session = login()
        val weddingId = createWedding(session)
        val before = updatedAt(weddingId)

        val response = patch("/weddings/$weddingId", listOf(session), """{}""")

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.json()["wedding"]["weddingDate"].asText()).isEqualTo("2026-10-10")
        assertThat(updatedAt(weddingId)).isEqualTo(before)
    }

    @Test
    fun `a wedding the caller holds no seat in answers exactly what a nonexistent one answers`() {
        val session = login()
        val weddingId = createWedding(session)
        val stranger = loginAs("a-stranger")

        val theirs = patch("/weddings/$weddingId", listOf(stranger), """{"guaranteedHeadcount":150}""")
        val nobodys = patch("/weddings/${weddingId + 9999}", listOf(stranger), """{"guaranteedHeadcount":150}""")

        assertThat(theirs.statusCode()).isEqualTo(404)
        assertThat(withoutInstance(theirs)).isEqualTo(withoutInstance(nobodys))
        // And the refusal wrote nothing: the number the couple sees is still theirs.
        assertThat(headcount(session, weddingId).has("guaranteedHeadcount")).isFalse()
    }

    @Test
    fun `a stranger sending an unreadable body is still told only that there is no wedding`() {
        val session = login()
        val weddingId = createWedding(session)
        val stranger = loginAs("a-stranger")

        // The resolver runs before the body is read, so the parse failure never
        // happens. If it did, a 400 would tell someone with no claim on this wedding
        // that the id resolves — the oracle the 404 exists to close.
        val response = patch("/weddings/$weddingId", listOf(stranger), """{"guaranteedHeadcount":"""")

        assertThat(response.statusCode()).isEqualTo(404)
        assertThat(response.json()["code"].asText()).isEqualTo("WEDDING_NOT_FOUND")
    }

    @Test
    fun `an anonymous request is refused before the body is looked at`() {
        val session = login()
        val weddingId = createWedding(session)

        val response = patch("/weddings/$weddingId", body = """{"guaranteedHeadcount":0}""")

        assertThat(response.statusCode()).isEqualTo(401)
        assertThat(response.json()["code"].asText()).isEqualTo("UNAUTHENTICATED")
    }

    @Test
    fun `the partner edits the same wedding — the entitlement to change it is the seat`() {
        val session = login()
        val weddingId = createWedding(session)
        val partner = joinAsPartner(weddingId)

        val response = patch("/weddings/$weddingId", listOf(partner), """{"guaranteedHeadcount":150}""")

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(headcount(session, weddingId)["guaranteedHeadcount"].asInt()).isEqualTo(150)
    }

    private fun headcount(
        session: HttpCookie,
        weddingId: Long,
    ) = get("/weddings/$weddingId/headcount", listOf(session)).json()

    private fun updatedAt(weddingId: Long): Instant =
        jdbc
            .queryForObject("select updated_at from wedding where id = ?", java.sql.Timestamp::class.java, weddingId)!!
            .toInstant()
}
