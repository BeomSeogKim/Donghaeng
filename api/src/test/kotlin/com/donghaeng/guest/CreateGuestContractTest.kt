package com.donghaeng.guest

import com.donghaeng.auth.StubGoogleRegistration
import com.donghaeng.wedding.WeddingSide
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.net.HttpCookie
import java.net.http.HttpResponse

/**
 * THE RED GATE OF `#11`'s backend half: the couple types a name and the ledger has
 * a row in it.
 *
 * Driven over real HTTP against a real Postgres carrying `V1`, with a session earned
 * by completing an actual OAuth round trip ([ApiFixture.login]) — a test that called
 * [GuestService] directly would pass with the argument resolver unwired, which is
 * the failure the auth-gate record says must not be shippable.
 *
 * Three things are asserted from the DATABASE rather than from the response, because
 * the response deliberately does not carry them and a reviewer cannot see them
 * otherwise: which wedding the row landed in, who is recorded as having written it,
 * and that the confirmed slots are still NULL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Import(StubGoogleRegistration::class)
internal class CreateGuestContractTest : GuestFixture() {
    @Test
    fun `a name and a side are the whole of it, and the defaults fill in the rest`() {
        val session = login()
        val userId = callerId(session)
        val weddingId = createWedding(session)

        val response = addGuest(session, weddingId, """{"name":"김영수","side":"GROOM"}""")

        assertThat(response.statusCode()).isEqualTo(201)
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json")

        val guest = response.json()["guest"]
        assertThat(guest["id"].asLong()).isPositive()
        assertThat(guest["name"].asText()).isEqualTo("김영수")
        assertThat(guest["side"].asText()).isEqualTo("GROOM")

        // The three recorded defaults (notes/2026-08-06-design-ledger-and-import.md
        // §4, and 기타 as the one category that means "not stated"). Each is a
        // decision, so each is asserted rather than left to whatever the DTO happens
        // to do.
        assertThat(guest["expectedAttending"].asBoolean()).isTrue()
        assertThat(guest["expectedPartySize"].asInt()).isEqualTo(1)
        assertThat(guest["groupCategory"].asText()).isEqualTo("OTHER")
        assertThat(guest["groupLabel"].isNull).isTrue()
        assertThat(guest["contact"].isNull).isTrue()
        assertThat(guest["accessibilityNote"].isNull).isTrue()

        val row = rows(weddingId).single()
        assertThat(row["wedding_id"]).isEqualTo(weddingId)
        assertThat(row["created_by"]).isEqualTo(userId)
        assertThat(row["updated_by"]).isEqualTo(userId)

        // **Blank is UNKNOWN, never zero and never 불참**
        // (notes/2026-08-03-design-domain-model.md §1). Couple input writes the
        // expected slots only, so a row created here is "expected, unverified" — and
        // nothing in the response can say so, which is why this is read back.
        assertThat(row["confirmed_attending"]).isNull()
        assertThat(row["confirmed_party_size"]).isNull()
        assertThat(row["deleted_at"]).isNull()
    }

    @Test
    fun `every optional field is stored and echoed as stored`() {
        val session = login()
        val weddingId = createWedding(session)

        val response =
            addGuest(
                session,
                weddingId,
                """
                {"name":"이영희","side":"BRIDE","groupCategory":"FRIEND","groupLabel":"대학교 동아리 친구들",
                 "contact":"010-1234-5678","accessibilityNote":"휠체어 좌석","expectedAttending":false,
                 "expectedPartySize":3}
                """.trimIndent(),
            )

        assertThat(response.statusCode()).isEqualTo(201)
        val guest = response.json()["guest"]
        assertThat(guest["side"].asText()).isEqualTo("BRIDE")
        assertThat(guest["groupCategory"].asText()).isEqualTo("FRIEND")
        assertThat(guest["groupLabel"].asText()).isEqualTo("대학교 동아리 친구들")
        assertThat(guest["contact"].asText()).isEqualTo("010-1234-5678")
        assertThat(guest["accessibilityNote"].asText()).isEqualTo("휠체어 좌석")

        // 불참 is spelled attendance false, never a party size of 0 — the column's
        // CHECK refuses 0 and this is the row that proves the two are separate.
        assertThat(guest["expectedAttending"].asBoolean()).isFalse()
        assertThat(guest["expectedPartySize"].asInt()).isEqualTo(3)

        assertThat(rows(weddingId).single()["side"].toString()).isEqualTo(WeddingSide.BRIDE.name)
    }

    @Test
    fun `an omitted optional member and an explicit null mean the same thing`() {
        // Stated as a test because it is a choice: the optional members are nullable
        // AND defaulted, so `web/` may send `null` for a control the couple left
        // alone instead of building the body conditionally. A non-null Kotlin
        // property with a default would answer 400 to the same request.
        val session = login()
        val weddingId = createWedding(session)

        val response =
            addGuest(
                session,
                weddingId,
                """
                {"name":"박민수","side":"GROOM","groupCategory":null,"groupLabel":null,"contact":null,
                 "accessibilityNote":null,"expectedAttending":null,"expectedPartySize":null}
                """.trimIndent(),
            )

        assertThat(response.statusCode()).isEqualTo(201)
        val guest = response.json()["guest"]
        assertThat(guest["groupCategory"].asText()).isEqualTo("OTHER")
        assertThat(guest["expectedAttending"].asBoolean()).isTrue()
        assertThat(guest["expectedPartySize"].asInt()).isEqualTo(1)
    }

    @Test
    fun `the response is the changed row and the number it moved`() {
        // The envelope `#151` filled in rather than replaced: `web/` generates its
        // types from this, so `{guest}` GAINING a `headcount` is additive where a
        // bare GuestResponse becoming `{guest, headcount}` would have been a
        // frontend build break (notes/2026-08-20-decision-mutation-response-envelope.md).
        //
        // The number is asserted here as well as in HeadcountContractTest, and for a
        // different reason: it is read inside the write transaction, so a headcount
        // computed a moment too early would answer 0 for the very guest that was
        // just added — the ledger and the total disagreeing on one screen.
        val session = login()
        val weddingId = createWedding(session)

        val body = addGuest(session, weddingId, """{"name":"김영수","side":"GROOM","expectedPartySize":3}""").json()

        assertThat(body.fieldNames().asSequence().toList()).containsExactlyInAnyOrder("guest", "headcount")
        assertThat(body["headcount"]["mealHeadcount"].asInt()).isEqualTo(3)
        // 보증인원 is the venue's number and nothing in v1 sets it, so the member is
        // absent — never a null, and never a zero.
        assertThat(body["headcount"].has("guaranteedHeadcount")).isFalse()
    }

    @Test
    fun `an anonymous request is 401, and is refused before its body is read`() {
        val weddingId = createWedding(login())

        // The body is invalid on purpose. An anonymous caller must get one answer,
        // not one that depends on what they sent — which is a statement about the
        // ORDER of the handler's parameters, and it fails if they are swapped.
        val response = post("/weddings/$weddingId/guests", emptyList(), """{"name":""}""")

        assertThat(response.statusCode()).isEqualTo(401)
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json")
        assertThat(response.json()["code"].asText()).isEqualTo("UNAUTHENTICATED")
        assertThat(rows(weddingId)).isEmpty()
    }

    @Test
    fun `a logged-in stranger is told exactly what a nonexistent wedding is told`() {
        val weddingId = createWedding(login())

        // **The outsider gets a wedding of their own first**, and that line is the
        // test: without it, "not a member of THIS wedding" and "not a member of
        // anything" are the same state, so a resolver that dropped `weddingId` from
        // its seat query would hand every couple every other couple's ledger
        // and stay green (notes/2026-08-19-decision-wedding-scope-gate.md §2b).
        val outsider = loginAs("someone-else")
        createWedding(outsider)

        val refused = addGuest(outsider, weddingId, """{"name":"김영수","side":"GROOM"}""")
        val nonexistent = addGuest(outsider, weddingId + 10_000, """{"name":"김영수","side":"GROOM"}""")

        assertThat(refused.statusCode()).isEqualTo(404)
        assertThat(refused.json()["code"].asText()).isEqualTo("WEDDING_NOT_FOUND")
        assertThat(withoutInstance(refused)).isEqualTo(withoutInstance(nonexistent))
        assertThat(rows(weddingId)).isEmpty()
    }

    @Test
    fun `the partner writes into the ledger they were let into, as themselves`() {
        // The accepted half of the tenancy question, and the half the two tests above
        // do not cover: they say who is REFUSED, and a resolver that refused everyone
        // would pass both.
        //
        // **It is the partner and not a second wedding of the caller's since
        // 2026-08-21**: the one-wedding index made "one caller, two weddings" an
        // unrepresentable row, and with a caller holding exactly one wedding a
        // resolver scoped to the caller writes into the same place as one scoped to
        // the path. What stayed observable is the shape this product actually has —
        // two accounts in one ledger — where a write attributed or scoped by CALLER
        // instead of by WEDDING is a 하객 in the wrong place or under the wrong name.
        val session = login()
        val wedding = createWedding(session)
        val partner = joinAsPartner(wedding)
        val partnerId = callerId(partner)
        val outsider = loginAs("someone-else")
        val theirs = createWedding(outsider)

        val response = addGuest(partner, wedding, """{"name":"김영수","side":"GROOM"}""")

        assertThat(response.statusCode()).isEqualTo(201)
        assertThat(rows(theirs)).isEmpty()

        // `created_by` is the partner's, not the wedding creator's: `#25`'s audit
        // trail answers "이 숫자 누가 바꿨어?" and a couple is two people.
        val written = rows(wedding).single()
        assertThat(written["created_by"]).isEqualTo(partnerId)
    }

    @Test
    fun `a soft-deleted wedding cannot be written to, though the seat is live`() {
        val session = login()
        val weddingId = createWedding(session)
        jdbc.update("update wedding set deleted_at = now() where id = ?", weddingId)

        val response = addGuest(session, weddingId, """{"name":"김영수","side":"GROOM"}""")

        // This endpoint never reads the wedding, so the resolver is the ONLY thing
        // between a deleted wedding and a row written into it — the case the scope
        // record says every wedding-scoped endpoint but `GET /weddings/{weddingId}`
        // depends on the resolver for.
        assertThat(response.statusCode()).isEqualTo(404)
        assertThat(response.json()["code"].asText()).isEqualTo("WEDDING_NOT_FOUND")
        assertThat(rows(weddingId)).isEmpty()
    }

    @Test
    fun `a wedding id in the body chooses nothing`() {
        // The spec promises `web/` that the wedding id travels in the path and
        // nowhere else, and `ResolvedPrincipalTest` refuses the field on any bound
        // type. This is the runtime half: an unknown member is ignored, so a caller
        // who sends one writes into the wedding they are a member of and not the one
        // they named.
        val session = login()
        val mine = createWedding(session)
        val theirs = createWedding(loginAs("someone-else"))

        val response = addGuest(session, mine, """{"name":"김영수","side":"GROOM","weddingId":$theirs}""")

        assertThat(response.statusCode()).isEqualTo(201)
        assertThat(rows(mine)).hasSize(1)
        assertThat(rows(theirs)).isEmpty()
    }

    @Test
    fun `a name that is blank, whitespace, or too long for its column is 400`() {
        val session = login()
        val weddingId = createWedding(session)

        // `varchar(100)`, and the column is not the validator: unvalidated, the 101st
        // character is refused by Postgres, i.e. as a masked 500
        // (notes/2026-08-17-decision-log-masking-mechanism.md).
        val rejected =
            listOf(
                """{"name":"","side":"GROOM"}""",
                """{"name":"   ","side":"GROOM"}""",
                """{"name":"${"가".repeat(101)}","side":"GROOM"}""",
                """{"name":"김영수","side":"GROOM","groupLabel":"${"가".repeat(101)}"}""",
                """{"name":"김영수","side":"GROOM","contact":"${"0".repeat(31)}"}""",
                """{"name":"김영수","side":"GROOM","accessibilityNote":"${"가".repeat(501)}"}""",
            )

        rejected.forEach { body ->
            val response = addGuest(session, weddingId, body)
            assertThat(response.statusCode()).describedAs(body).isEqualTo(400)
            assertThat(response.json()["code"].asText()).describedAs(body).isEqualTo("VALIDATION_FAILED")
        }

        // **Each bound is exercised at exactly the column's width and ACCEPTED**,
        // which is what stops a storage limit from silently becoming a narrower
        // product rule nobody recorded (the `@StorableDate` argument, `#123`). One
        // request rather than four: narrowing any single `@Size` — `contact` to 20,
        // `accessibilityNote` to 400 — turns this 201 into a 400, which is the
        // failure a name-only check could not see.
        val atEveryBound =
            addGuest(
                session,
                weddingId,
                """{"name":"${"가".repeat(100)}","side":"GROOM","groupLabel":"${"가".repeat(100)}",""" +
                    """"contact":"${"0".repeat(30)}","accessibilityNote":"${"가".repeat(500)}"}""",
            )

        assertThat(atEveryBound.statusCode()).isEqualTo(201)
        assertThat(rows(weddingId)).hasSize(1)
    }

    @Test
    fun `a party of zero is not a party, and the upper bound is the column's own`() {
        val session = login()
        val weddingId = createWedding(session)

        listOf(0, -1).forEach { size ->
            val response = addGuest(session, weddingId, """{"name":"김영수","side":"GROOM","expectedPartySize":$size}""")
            assertThat(response.statusCode()).describedAs("$size").isEqualTo(400)
            assertThat(response.json()["code"].asText()).describedAs("$size").isEqualTo("VALIDATION_FAILED")
        }

        // The largest `integer` the column can hold is accepted, which is what stops
        // a storage limit from silently becoming an unrecorded product rule: nobody
        // has decided that a party of 500 is impossible, and inventing that bound
        // here would refuse a real couple (the `@StorableDate` argument, `#123`).
        assertThat(
            addGuest(session, weddingId, """{"name":"김영수","side":"GROOM","expectedPartySize":2147483647}""").statusCode(),
        ).isEqualTo(201)

        // One past it is a body that cannot be read, not a value that was refused —
        // a different code for the same user-facing meaning, as the spec says.
        val overflowed = addGuest(session, weddingId, """{"name":"김영수","side":"GROOM","expectedPartySize":2147483648}""")
        assertThat(overflowed.statusCode()).isEqualTo(400)
        assertThat(overflowed.json()["code"].asText()).isEqualTo("MALFORMED_REQUEST_BODY")
    }

    @Test
    fun `a name or a side that is missing, and a category outside the seven, are 400`() {
        val session = login()
        val weddingId = createWedding(session)

        val rejected =
            listOf(
                """{"side":"GROOM"}""",
                """{"name":"김영수"}""",
                """{"name":"김영수","side":null}""",
                """{"name":"김영수","side":"BOTH"}""",
                """{"name":"김영수","side":"GROOM","groupCategory":"BEST_FRIEND"}""",
            )

        rejected.forEach { body ->
            val response = addGuest(session, weddingId, body)
            assertThat(response.statusCode()).describedAs(body).isEqualTo(400)
            // The value never becomes a row: an eighth group category is refused by
            // this application and not by the `varchar(30)` it would have fitted in,
            // which is the whole of "varchar plus application-level validation".
            assertThat(response.json()["code"].asText()).describedAs(body).isEqualTo("MALFORMED_REQUEST_BODY")
        }

        assertThat(rows(weddingId)).isEmpty()
    }

    @Test
    fun `values are stored trimmed, and a blank optional field is stored as nothing`() {
        // To this schema `' 김영수'` and `'김영수'` are two different names, and the
        // import matcher reads what was stored. Emptying to NULL is the other half:
        // an empty string would render on the ledger as a contact the couple never
        // gave.
        val session = login()
        val weddingId = createWedding(session)

        val response =
            addGuest(
                session,
                weddingId,
                """{"name":"  김영수 ","side":"GROOM","groupLabel":"  ","contact":" 010-1234-5678 ","accessibilityNote":""}""",
            )

        val guest = response.json()["guest"]
        assertThat(guest["name"].asText()).isEqualTo("김영수")
        assertThat(guest["contact"].asText()).isEqualTo("010-1234-5678")
        assertThat(guest["groupLabel"].isNull).isTrue()
        assertThat(guest["accessibilityNote"].isNull).isTrue()

        val row = rows(weddingId).single()
        assertThat(row["name"]).isEqualTo("김영수")
        assertThat(row["group_label"]).isNull()
    }

    @Test
    fun `two guests of the same name are two rows, because direct entry names a person`() {
        // Every OTHER intake channel converges on the matching pipeline, which
        // answers "who does this row mean". Here the couple is looking at the ledger
        // and naming a person into it, so the question is not asked and a 동명이인 is
        // simply a second row (`#11`).
        val session = login()
        val weddingId = createWedding(session)

        val first = addGuest(session, weddingId, """{"name":"김영수","side":"GROOM"}""")
        val second = addGuest(session, weddingId, """{"name":"김영수","side":"GROOM"}""")

        assertThat(first.statusCode()).isEqualTo(201)
        assertThat(second.statusCode()).isEqualTo(201)
        assertThat(first.json()["guest"]["id"].asLong()).isNotEqualTo(second.json()["guest"]["id"].asLong())
        assertThat(rows(weddingId)).hasSize(2)
    }

    private fun addGuest(
        session: HttpCookie,
        weddingId: Long,
        body: String,
    ): HttpResponse<String> = post("/weddings/$weddingId/guests", listOf(session), body)

    private fun rows(weddingId: Long): List<Map<String, Any?>> =
        jdbc.queryForList("select * from guest where wedding_id = ? order by id", weddingId)
}
