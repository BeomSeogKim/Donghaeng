package com.donghaeng.guest

/**
 * What a write to the ledger answers: the row that changed, and — once `#17` exists
 * — the number it moved
 * (notes/2026-08-20-decision-mutation-response-envelope.md).
 *
 * **A one-member wrapper is not redundant here; do not inline it to a bare
 * [GuestResponse].** That record is why: the wrapper is what makes `headcount`
 * additive to `web/`'s generated types, and why the member is absent rather than
 * null until `#17` decides what it counts.
 */
data class GuestMutationResponse(
    val guest: GuestResponse,
)

internal fun Guest.toGuestMutationResponse() = GuestMutationResponse(guest = toGuestResponse())
