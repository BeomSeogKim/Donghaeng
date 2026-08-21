package com.donghaeng.wedding

import java.time.LocalDate

/**
 * A wedding as the API publishes it — public, because `web/` generates a TypeScript
 * type from this shape.
 *
 * **[seats] replaced `groomName` and `brideName`** (changed 2026-08-22,
 * `notes/2026-08-22-decision-the-couples-two-seats.md`). Those were two strings on
 * the wedding describing two people, with nothing joining either of them to the
 * account that person logs in with; the seat is the concept both were fragments of,
 * so the header the ledger renders now reads the pair rather than two columns.
 *
 * **Always two entries, 신랑 먼저.** A wedding has exactly two seats from the moment
 * it exists, so a client may index the array — but reading `side` is what makes that
 * correct rather than lucky, and it is one field either way.
 *
 * **No `guaranteedHeadcount`**, for the reason `MeResponse` has no `email`: nothing
 * sets it here and no screen reads it from this response, and a published field
 * nothing consumes is a seam commitment with no requirement behind it. `#8` adds it
 * with the endpoint that can set it. **No `createdBy`** either — who created a
 * wedding is an audit fact, not something a screen renders — and **no subscription**,
 * since nothing pays and nothing is refused yet
 * (`notes/2026-08-22-decision-entitlement-belongs-to-the-wedding.md` §6).
 */
data class WeddingResponse(
    val id: Long,
    val weddingDate: LocalDate,
    val seats: List<WeddingSeatResponse>,
)

/**
 * The order is decided HERE rather than by the query, so that 신랑 먼저 is a property
 * of the published shape and not of the order two words were typed in inside
 * `create type wedding_side`. [WeddingSide]'s declaration order is the wire order,
 * which is why this walks the enum instead of sorting the rows.
 */
internal fun Wedding.toWeddingResponse(seats: List<WeddingSeat>) =
    WeddingResponse(
        id = id,
        weddingDate = weddingDate,
        seats = WeddingSide.entries.mapNotNull { side -> seats.firstOrNull { it.side == side }?.toWeddingSeatResponse() },
    )
