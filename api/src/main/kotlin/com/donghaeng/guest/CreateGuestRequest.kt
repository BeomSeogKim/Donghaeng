package com.donghaeng.guest

import com.donghaeng.wedding.WeddingSide
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * **The upper bound on a party, and it is an operational bound rather than a domain
 * rule** — the one thing here that no `notes/` record decides.
 *
 * Until 2026-08-23 this number was stored in a column, so any `integer` was as cheap
 * as any other and the spec said so deliberately: nobody had decided a party of 500
 * was impossible. **`#213` made it a row count**, and one request now writes as many
 * rows as it asks for — so an unbounded member is an unbounded write, which is not a
 * product decision to leave open.
 *
 * 20 is chosen to be past anything a couple types into a 인원수 field and nowhere near
 * what a ledger would notice. If it is wrong it is one constant and a spec line.
 */
private const val MAX_PARTY_SIZE = 20L

/**
 * The body of `POST /weddings/{weddingId}/guests`, specified in `docs/api-spec.md`
 * — which also carries why [side] is required and why there is no `weddingId` and no
 * `confirmed*` member.
 *
 * **Every optional member is nullable AND defaulted**, so that omitting one and
 * sending it as `null` mean the same thing. A non-null Kotlin property with a
 * default would answer an explicit `null` with a different code than an invalid
 * value gets, and springdoc would publish it to `web/` as required.
 *
 * **Every `@Size` is the column's own width**, measured on the value as sent, before
 * [GuestService.create] trims it — the rule `CreateWeddingRequest` set. A cast is not
 * a validator, and unvalidated these are refused by Postgres, i.e. as a masked 500
 * (notes/2026-08-17-decision-log-masking-mechanism.md).
 *
 * **[expectedPartySize] still means the same thing and no longer lands in a column**
 * (changed 2026-08-23, `#213`): it is 참석 인원 including this guest, and the server
 * writes that many 하객 records — this one, plus `size - 1` companions carrying its
 * 측, its 참석 and a given name (notes/2026-08-23-decision-companions-become-guests.md).
 * The response is the party rather than the row.
 */
data class CreateGuestRequest(
    @field:NotBlank
    @field:Size(max = 100)
    @param:Schema(description = "The guest's name — the one thing the couple must type", example = "김영수")
    val name: String,
    @param:Schema(description = "신랑측 or 신부측. Required: there is no value that means 'not stated'")
    val side: WeddingSide,
    @param:Schema(description = "One of the seven aggregation groups. Omitted, the guest is OTHER (기타)")
    val groupCategory: GuestGroupCategory? = null,
    @field:Size(max = 100)
    @param:Schema(description = "The couple's own label for the group. Never aggregated on", example = "대학교 동아리 친구들")
    val groupLabel: String? = null,
    @field:Size(max = 30)
    @param:Schema(description = "As entered — normalising for matching is the matcher's job", example = "010-1234-5678")
    val contact: String? = null,
    @field:Size(max = 500)
    @param:Schema(description = "배려사항 — belongs to the person and carries forward to seating", example = "휠체어 좌석")
    val accessibilityNote: String? = null,
    @param:Schema(description = "예상 참석 여부. Omitted, it is 참석 — the couple corrects what they hear")
    val expectedAttending: Boolean? = null,
    @field:Min(1)
    @field:Max(MAX_PARTY_SIZE)
    @param:Schema(
        description = "참석 인원, this guest included — not a companion count. Each person becomes a 하객 record. Omitted, it is 1",
        example = "2",
        minimum = "1",
        maximum = "20",
    )
    val expectedPartySize: Int? = null,
)
