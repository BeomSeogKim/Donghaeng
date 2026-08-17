package com.donghaeng.wedding

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.LocalDate

/**
 * 웨딩 만들기 — the date and the two names, and deliberately nothing else.
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
 * `@Size(max = 100)` is the `varchar(100)` the names land in, and it measures the
 * value **as sent**, before [WeddingService.create] trims it; `@NotBlank` is what
 * refuses a name the trim would empty. Both types are non-null, so an omitted member
 * fails while the body is read — a 400 `MALFORMED_REQUEST_BODY` rather than a 400
 * `VALIDATION_FAILED`, which `docs/api-spec.md` states rather than smooths over.
 */
data class CreateWeddingRequest(
    @field:StorableDate
    @param:Schema(description = "The wedding date", example = "2026-10-10")
    val weddingDate: LocalDate,
    @field:NotBlank
    @field:Size(max = 100)
    @param:Schema(description = "The groom's name", example = "김신랑")
    val groomName: String,
    @field:NotBlank
    @field:Size(max = 100)
    @param:Schema(description = "The bride's name", example = "이신부")
    val brideName: String,
)
