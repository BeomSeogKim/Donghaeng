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
 * **[weddingName] is the wedding's own name and is nullable** (added 2026-08-23,
 * `#214`): it is what the ledger's header renders in place of the product word, and
 * `null` — the state a wedding is created in unless the couple typed one — is
 * ordinary rather than an error. It is spelled `weddingName` and not `name` because
 * the create request already carries a `name`, which is the caller's own; one
 * spelling everywhere beats two that differ per shape, and it reads beside
 * [weddingDate] the way it is stored beside it
 * (notes/2026-08-23-decision-the-wedding-has-a-name.md).
 *
 * **Always two entries, 신랑 먼저.** A wedding has exactly two seats from the moment
 * it exists, so a client may index the array — but reading `side` is what makes that
 * correct rather than lucky, and it is one field either way.
 *
 * **No `guaranteedHeadcount`**, and `#173` decided it stays that way rather than
 * adding one (notes/2026-08-22-decision-partial-update-shape.md §4): the PATCH that
 * writes 보증인원 answers `{wedding, headcount}`, so carrying it here too would put
 * one number in one response twice, and the two would be equal until the day one of
 * them was not. `headcount.guaranteedHeadcount` is the spelling.
 * **No `createdBy`** either — who created a
 * wedding is an audit fact, not something a screen renders — and **no subscription**,
 * since nothing pays and nothing is refused yet
 * (`notes/2026-08-22-decision-entitlement-belongs-to-the-wedding.md` §6).
 */
data class WeddingResponse(
    val id: Long,
    val weddingName: String?,
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
        weddingName = name,
        weddingDate = weddingDate,
        seats = WeddingSide.entries.mapNotNull { side -> seats.firstOrNull { it.side == side }?.toWeddingSeatResponse() },
    )
