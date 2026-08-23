package com.donghaeng.wedding

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDate

/**
 * 웨딩 만들기 — the date and **the caller's own seat**, and deliberately nothing else.
 *
 * **[side] and [name] describe whoever is filling this in, never their partner**
 * (changed 2026-08-22, `notes/2026-08-22-decision-the-couples-two-seats.md`). The
 * shape is the same size it was — three members — but the third is now "who I am"
 * rather than "who they are": a required field that had to be filled by whoever
 * arrived first was asking one partner to type the other's name. The partner's seat
 * is created empty and `#9`'s invite fills it.
 *
 * **보증인원 and meal types are absent because the couple does not know them yet**
 * (notes/2026-08-07-design-screens-and-flow.md §2, §3): they sign up before booking
 * a venue, and the ledger works completely without the venue's number. An unknown
 * member is ignored rather than refused, so sending `guaranteedHeadcount` here sets
 * nothing — asserted, since that is otherwise a claim about Jackson's defaults.
 *
 * **[weddingDate] is not compared to today.** A past date is a real case — a couple
 * building the ledger after the fact — and refusing one would be a domain policy no
 * record makes. [StorableDate] bounds it only where the column does.
 *
 * **[weddingName] is the only optional member, and it is the WEDDING's name rather
 * than anybody's** (added 2026-08-23, `#214`). Nullable AND defaulted, so omitting it
 * and sending `null` mean the same thing — a wedding with no name is the ordinary
 * state of one created in a hurry, and 설정 adds one later
 * (notes/2026-08-23-decision-the-wedding-has-a-name.md). It carries [WeddingName]
 * rather than [SeatName] because `#8`'s PATCH carries the same member inside a
 * `Patch`, which a composed `@Size` cannot read — that file has the argument.
 *
 * [SeatName] is the whole of the name rule — the `varchar(100)` it lands in, measured
 * as sent and before [WeddingService.create] trims it, and a refusal of a name the
 * trim would empty. It is one annotation rather than two written out here because
 * three requests carry a seat name. All three types are non-null, so an omitted
 * member — or a [side] that is neither `GROOM` nor `BRIDE` — fails while the body is
 * read: a 400 `MALFORMED_REQUEST_BODY` rather than a 400 `VALIDATION_FAILED`, which
 * `docs/api-spec.md` states rather than smooths over.
 */
data class CreateWeddingRequest(
    @field:StorableDate
    @param:Schema(description = "The wedding date", example = "2026-10-10")
    val weddingDate: LocalDate,
    @param:Schema(description = "Which seat the caller is taking — 신랑 or 신부. Never their partner's")
    val side: WeddingSide,
    @field:SeatName
    @param:Schema(description = "The caller's own name, as it should read on the ledger", example = "김신랑")
    val name: String,
    @field:WeddingName
    @param:Schema(
        description = "결혼식 이름 — the couple's own words, at most 100 characters. Omitted or null, the wedding has none",
        example = "범석 희주의 가을",
        nullable = true,
    )
    val weddingName: String? = null,
)
