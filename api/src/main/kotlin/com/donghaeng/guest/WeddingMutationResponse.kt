package com.donghaeng.guest

import com.donghaeng.wedding.WeddingResponse

/**
 * What a write to the wedding itself answers: the wedding, and the number that
 * write moved (notes/2026-08-20-decision-mutation-response-envelope.md).
 *
 * The same family as [GuestMutationResponse] — `{resource, headcount}` — because the
 * rule is about mutations on wedding-scoped resources and not about the ledger:
 * 보증인원 lives inside [HeadcountResponse], so a couple who sets it and then had to
 * refetch the headcount would be reading their own write back over a second round
 * trip.
 *
 * **[wedding] does not carry the 보증인원 and this is where that is decided.** One
 * response may not spell one number two ways; `headcount.guaranteedHeadcount` is the
 * spelling, here and in `GET /weddings/{weddingId}/headcount` alike
 * (notes/2026-08-22-decision-partial-update-shape.md §4).
 */
data class WeddingMutationResponse(
    val wedding: WeddingResponse,
    val headcount: HeadcountResponse,
)
