package com.donghaeng.guest

/**
 * What a write to the ledger answers: the row that changed, and — once `#17` exists
 * — the number it moved (root AGENTS.md, Standing product facts).
 *
 * **The wrapper exists because `headcount` is coming.** `web/` generates its types
 * from this class, so `{ guest }` gaining a second member is additive where a bare
 * [GuestResponse] becoming `{ guest, headcount }` is a frontend build break. The
 * member with nothing behind it is absent rather than null: computing a number
 * before `#17` decides what it counts would be a wrong number, and a wrong number
 * here is money.
 */
data class GuestMutationResponse(
    val guest: GuestResponse,
)

internal fun Guest.toGuestMutationResponse() = GuestMutationResponse(guest = toGuestResponse())
