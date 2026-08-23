package com.donghaeng.guest

/**
 * What a write to the ledger answers: the resource that changed, and the number it
 * moved (notes/2026-08-20-decision-mutation-response-envelope.md).
 *
 * **The resource is the PARTY, and the member is named `party`** (changed 2026-08-23,
 * `#213`). 하객 추가 writes one row for a party of one and three rows for a party of
 * three, so a `guest` member would have had to answer with one of the three and leave
 * the screen to fetch the rest — on the one screen where the ledger and the number
 * must agree without a second round trip. `web/` reads `response.party` and
 * `response.headcount`.
 *
 * **[headcount] is not optional**: the ledger and the headcount are one screen, so a
 * tap moves the number without a second request, and a mutation that answered without
 * it would send the client back for the row it just changed.
 */
data class GuestMutationResponse(
    val party: GuestPartyResponse,
    val headcount: HeadcountResponse,
)
