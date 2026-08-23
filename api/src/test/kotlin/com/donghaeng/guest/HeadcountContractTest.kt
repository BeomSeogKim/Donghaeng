package com.donghaeng.guest

import com.donghaeng.auth.StubGoogleRegistration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.net.HttpCookie
import java.net.http.HttpResponse

/**
 * THE RED GATE OF `#151` (the backend half of `#17`), and **the tests are the issue
 * rather than the trim on it**: 틀린 숫자는 조용히 나간다. A wrong ledger is visible
 * on the screen that shows it; a wrong headcount is a plausible number that the
 * couple takes to their venue, and 보증인원 is money.
 *
 * So each test below pins one way the number can be silently wrong — a 불참 하객
 * counted, a deleted one counted, another wedding's counted, an empty ledger
 * answering anything but zero, a 보증인원 invented — rather than pinning the response
 * shape, which the seam already type-checks.
 *
 * Driven over real HTTP against a real Postgres carrying `V1`, because the two
 * predicates that decide the number are SQL: `@SQLRestriction` does not reach every
 * query shape, and a `wedding_id` left off an aggregation compiles perfectly
 * (notes/2026-08-10-decision-soft-delete.md, consequence 1).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Import(StubGoogleRegistration::class)
internal class HeadcountContractTest : GuestFixture() {
    @Test
    fun `the number is the count of attending 하객 records`() {
        // 인원수 3 is three records (2026-08-23, `#213`), so a party of three still
        // adds three — the same number for a party that agrees with itself, and a
        // truer one for one that does not.
        val session = login()
        val weddingId = createWedding(session)
        addGuest(session, weddingId, """{"name":"김영수","side":"GROOM","expectedPartySize":3}""")
        addGuest(session, weddingId, """{"name":"이영희","side":"BRIDE"}""")

        val response = headcount(session, weddingId)

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json")
        assertThat(response.json()["mealHeadcount"].asInt()).isEqualTo(4)
    }

    @Test
    fun `a 불참 guest contributes zero, whatever their party size says`() {
        // **Attendance is read BEFORE party size**
        // (notes/2026-08-20-decision-guest-entry-side-and-companions.md §3): the size
        // is kept so that flipping back to 참석 restores it, which is exactly why the
        // number must not read it. A `sum(expected_party_size)` with the attendance
        // predicate dropped answers 6 here and looks entirely reasonable.
        val session = login()
        val weddingId = createWedding(session)
        addGuest(session, weddingId, """{"name":"김영수","side":"GROOM","expectedPartySize":2}""")
        addGuest(session, weddingId, """{"name":"이영희","side":"BRIDE","expectedPartySize":4,"expectedAttending":false}""")

        assertThat(mealHeadcount(session, weddingId)).isEqualTo(2)
    }

    @Test
    fun `a soft-deleted guest contributes zero, and a deleted head does not take its party`() {
        // The failure this issue exists to close, and stated as what it is: today
        // TWO things stop it — the predicate in the query and `@SQLRestriction`,
        // which reaches JPQL — so this test cannot fail by one of them being
        // dropped, and that was checked by dropping it.
        //
        // It is not therefore decoration. A deleted 하객 missing from the ledger is
        // visible on screen; a deleted 하객 inside a sum is not, and the rewrite this
        // query is scheduled for (`#14`, the join to `guest_meal_count`) is exactly
        // the shape the ambient filter stops reaching
        // (notes/2026-08-10-decision-soft-delete.md, consequence 1). This is the
        // assertion that goes red then.
        val session = login()
        val weddingId = createWedding(session)
        addGuest(session, weddingId, """{"name":"김영수","side":"GROOM","expectedPartySize":2}""")
        val removed = addGuest(session, weddingId, """{"name":"이영희","side":"BRIDE","expectedPartySize":5}""")

        // **Deleting the head deletes one PERSON, not the party** (2026-08-23, `#213`):
        // its four companions are 하객 records of their own and go on being counted.
        // Whether a delete should offer to take them is a real question and it is not
        // this issue's — **no endpoint deletes a 하객 in v1 at all**, so there is
        // nowhere for that answer to live yet.
        jdbc.update("update guest set deleted_at = now() where id = ?", removed)
        assertThat(mealHeadcount(session, weddingId)).isEqualTo(6)

        jdbc.update("update guest set deleted_at = now() where companion_of = ?", removed)
        assertThat(mealHeadcount(session, weddingId)).isEqualTo(2)
    }

    @Test
    fun `the partner's guests are counted and another wedding's are not`() {
        // Both directions of the same mutation, on the number that is money.
        // **Scoped to the CALLER**: the 하객 the partner entered is not counted, and
        // the couple books a hall for fewer people than are coming. **Scoped to
        // nothing**: a stranger's ledger is added to theirs. Neither shows up as an
        // error — they show up as a wrong 보증인원
        // (notes/2026-08-19-decision-wedding-scope-gate.md §2b).
        //
        // The second wedding belongs to a stranger rather than to the caller since
        // 2026-08-21: `ux_party_user` refuses the caller a second seat,
        // and a wedding with two people in it is where a caller-scoped sum still
        // goes wrong.
        val session = login()
        val here = createWedding(session)
        val partner = joinAsPartner(here)
        val outsider = loginAs("someone-else")
        val there = createWedding(outsider)

        addGuest(session, here, """{"name":"김영수","side":"GROOM","expectedPartySize":2}""")
        addGuest(partner, here, """{"name":"이영희","side":"BRIDE","expectedPartySize":3}""")
        addGuest(outsider, there, """{"name":"박철수","side":"GROOM","expectedPartySize":7}""")

        assertThat(mealHeadcount(session, here)).isEqualTo(5)
        assertThat(mealHeadcount(partner, here)).isEqualTo(5)
        assertThat(mealHeadcount(outsider, there)).isEqualTo(7)
    }

    @Test
    fun `a wedding with no guests answers 200 and zero`() {
        // The first screen a newly created wedding opens, and the reason the sum is
        // coalesced: `sum` over no rows is NULL, not 0.
        val session = login()
        val weddingId = createWedding(session)

        val response = headcount(session, weddingId)

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.json()["mealHeadcount"].asInt()).isZero()
    }

    @Test
    fun `보증인원 is absent until the venue's number is set, and is never invented`() {
        // Absent, not null and not zero: 비어 있다 means the couple has not agreed a
        // number with their venue, and a zero there would read as a contract for
        // nobody (notes/2026-08-21-decision-the-headcount-endpoint.md §2).
        //
        // We never derive it either — no recommendation, no difference, no
        // percentage. 대비 계산은 클라이언트의 뺄셈이다.
        val session = login()
        val weddingId = createWedding(session)
        addGuest(session, weddingId, """{"name":"김영수","side":"GROOM"}""")

        assertThat(headcount(session, weddingId).json().has("guaranteedHeadcount")).isFalse()

        // Written through JDBC although `PATCH /weddings/{weddingId}` can now do it
        // (`#173`): this test is about what the READ publishes, and going through the
        // write path would make it fail for two reasons instead of one.
        jdbc.update("update wedding set guaranteed_headcount = 150 where id = ?", weddingId)

        val set = headcount(session, weddingId).json()
        assertThat(set["guaranteedHeadcount"].asInt()).isEqualTo(150)
        // Still two members and only two — no 미확인 count (참석 여부는 두 상태뿐), no
        // 응답률, no difference computed for the client.
        assertThat(set.fieldNames().asSequence().toList()).containsExactlyInAnyOrder("mealHeadcount", "guaranteedHeadcount")
    }

    @Test
    fun `the guests under the 참석 chip are exactly the guests this number counts`() {
        // **The one-screen invariant, asserted as an invariant rather than as a
        // spelling** (notes/2026-08-21-decision-the-headcount-endpoint.md §1). 원장과
        // 인원수는 한 화면이므로 a guest the ledger shows under 참석 may not be a guest
        // the number treats as 불참.
        //
        // The row that makes this bite carries a confirmed value, which nothing in
        // v1 writes (notes/2026-08-21-decision-attendance-is-two-states.md) — so it
        // is written through JDBC, as the ledger's own filter test writes it. Read
        // `expected_attending` alone and the chip and the number disagree here by
        // four people, with both endpoints answering 200.
        // Since `#213` the parties are what the chip returns, so the total is a sum of
        // `attendingCount` — and that member is folded in KOTLIN while the number is
        // counted in SQL. **This assertion is the only thing holding those two
        // spellings of `coalesce(confirmed, expected)` together.**
        val session = login()
        val weddingId = createWedding(session)
        addGuest(session, weddingId, """{"name":"김영수","side":"GROOM","expectedPartySize":2}""")
        val confirmedNot = addGuest(session, weddingId, """{"name":"이영희","side":"BRIDE","expectedPartySize":3}""")
        val confirmedYes =
            addGuest(session, weddingId, """{"name":"박민수","side":"GROOM","expectedPartySize":4,"expectedAttending":false}""")
        jdbc.update("update guest set confirmed_attending = false where id = ?", confirmedNot)
        jdbc.update("update guest set confirmed_attending = true where id = ?", confirmedYes)

        val attending = get("/weddings/$weddingId/guests?attendance=ATTENDING", listOf(session))
        val chipTotal = attending.json().sumOf { it["attendingCount"].asInt() }

        // 김영수's two, 이영희's two companions (only the head was confirmed 불참), and
        // 박민수 alone (only the head was confirmed 참석).
        assertThat(chipTotal).isEqualTo(5)
        assertThat(mealHeadcount(session, weddingId)).isEqualTo(chipTotal)
        // Every party the chip returns states the same count it was filtered by, which
        // is what stops a mixed party from putting a 불참 하객 inside the 참석 total.
        assertThat(attending.json().map { it["size"].asInt() }).isEqualTo(attending.json().map { it["attendingCount"].asInt() })
    }

    @Test
    fun `a logged-in stranger is told exactly what a nonexistent wedding is told`() {
        val session = login()
        val weddingId = createWedding(session)
        addGuest(session, weddingId, """{"name":"김영수","side":"GROOM"}""")

        // The outsider owns a wedding of their own, or "not a member of THIS wedding"
        // and "not a member of anything" are the same state.
        val outsider = loginAs("someone-else")
        createWedding(outsider)

        val refused = headcount(outsider, weddingId)
        val nonexistent = headcount(outsider, weddingId + 10_000)

        assertThat(refused.statusCode()).isEqualTo(404)
        assertThat(refused.json()["code"].asText()).isEqualTo("WEDDING_NOT_FOUND")
        assertThat(withoutInstance(refused)).isEqualTo(withoutInstance(nonexistent))
    }

    @Test
    fun `a soft-deleted wedding answers 404, though the seat is live`() {
        val session = login()
        val weddingId = createWedding(session)
        addGuest(session, weddingId, """{"name":"김영수","side":"GROOM"}""")
        jdbc.update("update wedding set deleted_at = now() where id = ?", weddingId)

        val response = headcount(session, weddingId)

        assertThat(response.statusCode()).isEqualTo(404)
        assertThat(response.json()["code"].asText()).isEqualTo("WEDDING_NOT_FOUND")
    }

    @Test
    fun `an anonymous request is 401, and an id that is not a number is 404`() {
        val session = login()
        val weddingId = createWedding(session)

        val anonymous = get("/weddings/$weddingId/headcount")
        assertThat(anonymous.statusCode()).isEqualTo(401)
        assertThat(anonymous.json()["code"].asText()).isEqualTo("UNAUTHENTICATED")

        // One answer for every id the caller may not have, including one that could
        // never be an id at all (notes/2026-08-10-decision-cross-tenant-status-code.md).
        val unparseable = get("/weddings/not-a-number/headcount", listOf(session))
        assertThat(unparseable.statusCode()).isEqualTo(404)
        assertThat(unparseable.json()["code"].asText()).isEqualTo("WEDDING_NOT_FOUND")
    }

    private fun headcount(
        session: HttpCookie,
        weddingId: Long,
    ): HttpResponse<String> = get("/weddings/$weddingId/headcount", listOf(session))

    private fun mealHeadcount(
        session: HttpCookie,
        weddingId: Long,
    ): Int = headcount(session, weddingId).json()["mealHeadcount"].asInt()

    private fun addGuest(
        session: HttpCookie,
        weddingId: Long,
        body: String,
    ): Long =
        post("/weddings/$weddingId/guests", listOf(session), body)
            .json()["party"]["id"]
            .asLong()
}
