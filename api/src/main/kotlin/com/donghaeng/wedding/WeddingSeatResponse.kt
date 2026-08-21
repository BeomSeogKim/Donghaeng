package com.donghaeng.wedding

/**
 * One seat as the API publishes it — public, because `web/` generates a TypeScript
 * type from this shape.
 *
 * **[name] is nullable and that is the ordinary case, not an error case**: a wedding
 * is created with one seat filled and one waiting, so until `#9`'s invite is accepted
 * the partner's seat has a side and nothing else. What the ledger header renders for
 * an empty seat is the frontend's to decide — it is copy on a screen, and it belongs
 * where the screen is (`notes/2026-08-22-decision-the-couples-two-seats.md` §5).
 *
 * **No `userId`, no `joinedAt`, no "is this me".** Nothing renders them today, and a
 * published field nothing consumes is a seam commitment with no requirement behind
 * it — the rule [WeddingResponse] already follows for `createdBy`. `#9` is the screen
 * that will need to tell an empty seat from a claimed one, and it adds what it needs.
 */
data class WeddingSeatResponse(
    val side: WeddingSide,
    val name: String?,
)

internal fun WeddingSeat.toWeddingSeatResponse() =
    WeddingSeatResponse(
        side = side,
        name = name,
    )
