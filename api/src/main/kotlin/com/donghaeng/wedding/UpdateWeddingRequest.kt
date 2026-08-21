package com.donghaeng.wedding

import com.donghaeng.json.NotCleared
import com.donghaeng.json.Patch
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode
import java.time.LocalDate

/**
 * The body of `PATCH /weddings/{weddingId}`, specified in `docs/api-spec.md` — the
 * product's first partial update, and the shape `#12` copies
 * (notes/2026-08-22-decision-partial-update-shape.md).
 *
 * **Every member is a [Patch] and every member is optional**, which is what makes
 * this a partial update rather than a replacement: a member the caller did not send
 * is not written, so one partner editing the date cannot blind-write the 보증인원
 * their form loaded before the other partner changed it. `wedding` carries no
 * `guest_change` trail, so an overwritten value here is simply gone — the reason
 * `Wedding` is `@DynamicUpdate` in the first place
 * (notes/2026-08-20-decision-row-concurrency-and-the-audit-trail.md).
 *
 * **The two members differ on `null` because the columns do.** 보증인원 has a real
 * unset state — a couple who has not signed with a venue, or has un-signed — so
 * `null` clears it. A wedding always has a date, so [weddingDate] wears
 * [NotCleared] and `null` is a 400 rather than a `not null` violation arriving as a
 * masked 500.
 *
 * **The couple's names are not here.** After 2026-08-22 a name belongs to a
 * `wedding_party` seat and not to the wedding, so editing one is `#175` and not this
 * endpoint (notes/2026-08-22-decision-the-couples-two-seats.md).
 *
 * The `@Schema` overrides are the seam, not decoration: `Patch` is a sealed
 * hierarchy, and left alone springdoc would publish its cases to `web/` as the shape
 * of a wedding date.
 */
data class UpdateWeddingRequest(
    @field:NotCleared
    @field:StorableDate
    @param:Schema(
        implementation = LocalDate::class,
        requiredMode = RequiredMode.NOT_REQUIRED,
        description = "예식일. Omit to leave it alone; it cannot be cleared, so `null` is a 400",
        example = "2026-10-10",
    )
    val weddingDate: Patch<LocalDate> = Patch.Absent,
    @field:StorableHeadcount
    @param:Schema(
        implementation = Int::class,
        requiredMode = RequiredMode.NOT_REQUIRED,
        nullable = true,
        description = "보증인원 — the venue's number. Omit to leave it alone, send `null` to go back to not having one",
        example = "150",
    )
    val guaranteedHeadcount: Patch<Int> = Patch.Absent,
)
