package com.donghaeng.guest

/**
 * What a write to the ledger answers: the row that changed, and the number it moved
 * (notes/2026-08-20-decision-mutation-response-envelope.md).
 *
 * **The second member arrived with `#151` and arrived as an ADDITION** — that is what
 * the wrapper was for. `{guest}` became `{guest, headcount}` without a call site in
 * `web/` changing, where a bare [GuestResponse] becoming an envelope would have been
 * a frontend build break.
 *
 * [headcount] is not optional now that it exists: the ledger and the headcount are
 * one screen, so a tap moves the number without a second round trip, and a mutation
 * that answered without it would send the client back for the row it just changed.
 */
data class GuestMutationResponse(
    val guest: GuestResponse,
    val headcount: HeadcountResponse,
)

internal fun Guest.toGuestMutationResponse(headcount: HeadcountResponse) =
    GuestMutationResponse(guest = toGuestResponse(), headcount = headcount)
