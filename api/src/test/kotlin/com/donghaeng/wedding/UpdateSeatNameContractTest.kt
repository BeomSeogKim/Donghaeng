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
import java.time.Instant

/**
 * `#187`'s RED GATE: `PUT /weddings/{weddingId}/seats/me` against `docs/api-spec.md`.
 *
 * The name entered in exactly two places before this endpoint — `POST /weddings` and
 * `POST /weddings/join` — and both are once-only, so a typo was permanent in a value
 * the ledger header renders on every screen.
 *
 * **What the assertions are really about is the seat the caller may write.** Nobody
 * types anybody else's name (notes/2026-08-22-decision-the-couples-two-seats.md), so
 * the endpoint addresses `me` and there is no way to spell the other seat — the
 * partner's row is asserted untouched after each of the two people renames their own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Import(StubGoogleRegistration::class)
internal class UpdateSeatNameContractTest : ApiFixture() {
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
    fun `the caller fixes their own name, and the answer is the wedding as it now stands`() {
        val session = login()
        val weddingId = createWedding(session)

        val response = put("/weddings/$weddingId/seats/me", listOf(session), """{"name":"김신랑입니다"}""")

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json")

        // The whole wedding, not the one seat: the ledger header renders the pair,
        // and this is the same `WeddingResponse` every other wedding endpoint answers.
        val wedding = response.json()
        assertThat(wedding["id"].asLong()).isEqualTo(weddingId)
        assertThat(wedding["weddingDate"].asText()).isEqualTo("2026-10-10")
        assertThat(wedding["seats"].map { it["side"].asText() }).containsExactly("GROOM", "BRIDE")
        assertThat(wedding["seats"][0]["name"].asText()).isEqualTo("김신랑입니다")
        // The partner's seat is still waiting, and this request had no way to fill it.
        assertThat(wedding["seats"][1]["name"].isNull).isTrue()

        assertThat(get("/weddings/$weddingId", listOf(session)).json()["seats"][0]["name"].asText()).isEqualTo("김신랑입니다")
    }

    @Test
    fun `each of the two people writes their own seat and nobody writes the other's`() {
        val session = login()
        val weddingId = createWedding(session)
        val partner = joinAsPartner(weddingId)

        assertThat(put("/weddings/$weddingId/seats/me", listOf(partner), """{"name":"이신부고침"}""").statusCode()).isEqualTo(200)
        assertThat(put("/weddings/$weddingId/seats/me", listOf(session), """{"name":"김신랑고침"}""").statusCode()).isEqualTo(200)

        assertThat(nameOf(weddingId, "GROOM")).isEqualTo("김신랑고침")
        assertThat(nameOf(weddingId, "BRIDE")).isEqualTo("이신부고침")
    }

    @Test
    fun `the name is trimmed at the write, exactly as the two places that enter one trim`() {
        val session = login()
        val weddingId = createWedding(session)

        val response = put("/weddings/$weddingId/seats/me", listOf(session), """{"name":"  김신랑  "}""")

        assertThat(response.json()["seats"][0]["name"].asText()).isEqualTo("김신랑")
        assertThat(nameOf(weddingId, "GROOM")).isEqualTo("김신랑")
    }

    @Test
    fun `a name the column would refuse is a 400, and nothing is written`() {
        val session = login()
        val weddingId = createWedding(session)

        listOf("\"\"", "\"   \"", "\"" + "가".repeat(101) + "\"").forEach { name ->
            val response = put("/weddings/$weddingId/seats/me", listOf(session), """{"name":$name}""")

            assertThat(response.statusCode()).describedAs("name=%s", name).isEqualTo(400)
            assertThat(response.json()["code"].asText()).describedAs("name=%s", name).isEqualTo("VALIDATION_FAILED")
        }
        assertThat(nameOf(weddingId, "GROOM")).isEqualTo("김신랑")
    }

    @Test
    fun `the name is required, and the two ways to leave it out fail while the body is read`() {
        val session = login()
        val weddingId = createWedding(session)

        // The member is non-null in Kotlin, so an omitted or null `name` never reaches
        // a validator — the same taxonomy `POST /weddings/join` publishes for its own
        // required members. `docs/api-spec.md` states it rather than smoothing it over.
        //
        // **A JSON number is not in this list, because it is not refused**: Jackson
        // coerces a scalar to a string API-wide, so `{"name":42}` renames the seat to
        // `"42"`. Asserted where it is true rather than claimed where it is not — the
        // spec entry says the same thing.
        listOf("""{}""", """{"name":null}""", """{"name":[]}""", """{"name":{}}""").forEach { body ->
            val response = put("/weddings/$weddingId/seats/me", listOf(session), body)

            assertThat(response.statusCode()).describedAs("%s", body).isEqualTo(400)
            assertThat(response.json()["code"].asText()).describedAs("%s", body).isEqualTo("MALFORMED_REQUEST_BODY")
        }
        assertThat(nameOf(weddingId, "GROOM")).isEqualTo("김신랑")
    }

    @Test
    fun `the same name resent does not claim the row was touched`() {
        val session = login()
        val weddingId = createWedding(session)
        val before = updatedAt(weddingId, "GROOM")

        val response = put("/weddings/$weddingId/seats/me", listOf(session), """{"name":"김신랑"}""")

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.json()["seats"][0]["name"].asText()).isEqualTo("김신랑")
        // A row reported as touched when it was not is a lie an audit read would
        // believe — the rule `PATCH /weddings/{weddingId}` already follows.
        assertThat(updatedAt(weddingId, "GROOM")).isEqualTo(before)
    }

    @Test
    fun `the response carries no headcount — a name cannot move the number`() {
        val session = login()
        val weddingId = createWedding(session)

        val response = put("/weddings/$weddingId/seats/me", listOf(session), """{"name":"김신랑고침"}""")

        // Bare `WeddingResponse`, not `{wedding, headcount}`: 인원수 is a fold over the
        // ledger and a seat's name is not in it, so there is no recomputed number to
        // publish — the same answer `POST /weddings/join` gives after writing the very
        // same column.
        assertThat(response.json().has("headcount")).isFalse()
        assertThat(response.json().has("wedding")).isFalse()
    }

    @Test
    fun `a wedding the caller holds no seat in answers exactly what a nonexistent one answers`() {
        val session = login()
        val weddingId = createWedding(session)
        val stranger = loginAs("a-stranger")

        val theirs = put("/weddings/$weddingId/seats/me", listOf(stranger), """{"name":"남의이름"}""")
        val nobodys = put("/weddings/${weddingId + 9999}/seats/me", listOf(stranger), """{"name":"남의이름"}""")

        assertThat(theirs.statusCode()).isEqualTo(404)
        assertThat(theirs.json()["code"].asText()).isEqualTo("WEDDING_NOT_FOUND")
        assertThat(withoutInstance(theirs)).isEqualTo(withoutInstance(nobodys))
        assertThat(nameOf(weddingId, "GROOM")).isEqualTo("김신랑")
    }

    @Test
    fun `a deleted wedding answers the same 404 as one that never existed`() {
        val session = login()
        val weddingId = createWedding(session)
        jdbc.update("update wedding set deleted_at = now() where id = ?", weddingId)

        val response = put("/weddings/$weddingId/seats/me", listOf(session), """{"name":"김신랑고침"}""")

        assertThat(response.statusCode()).isEqualTo(404)
        assertThat(response.json()["code"].asText()).isEqualTo("WEDDING_NOT_FOUND")
    }

    @Test
    fun `an anonymous request is refused before the body is looked at`() {
        val session = login()
        val weddingId = createWedding(session)

        val response = put("/weddings/$weddingId/seats/me", body = """{"name":""}""")

        assertThat(response.statusCode()).isEqualTo(401)
        assertThat(response.json()["code"].asText()).isEqualTo("UNAUTHENTICATED")
    }

    @Test
    fun `a stranger sending an unreadable body is still told only that there is no wedding`() {
        val session = login()
        createWedding(session)
        val stranger = loginAs("a-stranger")

        // The resolver runs before the body is read; a 400 here would tell somebody
        // with no claim on the wedding that the id resolves.
        val response = put("/weddings/1/seats/me", listOf(stranger), """{"name":""")

        assertThat(response.statusCode()).isEqualTo(404)
        assertThat(response.json()["code"].asText()).isEqualTo("WEDDING_NOT_FOUND")
    }

    private fun nameOf(
        weddingId: Long,
        side: String,
    ): String? =
        jdbc.queryForObject(
            "select name from wedding_party where wedding_id = ? and side = cast(? as wedding_side)",
            String::class.java,
            weddingId,
            side,
        )

    private fun updatedAt(
        weddingId: Long,
        side: String,
    ): Instant =
        jdbc
            .queryForObject(
                "select updated_at from wedding_party where wedding_id = ? and side = cast(? as wedding_side)",
                java.sql.Timestamp::class.java,
                weddingId,
                side,
            )!!
            .toInstant()
}
