package com.donghaeng.guest

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * 인원수 — the number the ledger screen is fixed to (`#151`, the backend half of
 * `#17`), and the member every wedding-scoped mutation carries back
 * (notes/2026-08-20-decision-mutation-response-envelope.md).
 *
 * **Two numbers, and there is no third.** 식대 인원 and 보증인원 — no 미확인 count
 * (참석 여부는 두 상태뿐이므로 그런 하객은 없다,
 * notes/2026-08-21-decision-attendance-is-two-states.md), no 응답률, and **no
 * difference between the two**: 대비 계산은 클라이언트의 뺄셈이고, we never emit a
 * recommendation built on a venue's buffer we have never seen.
 *
 * **[guaranteedHeadcount] is OMITTED, not null, when the couple has not set one** —
 * hence the `@JsonInclude`, which is doing contract work rather than tidying the
 * payload. Absent says "this response does not carry that", which is true; `null`
 * says "we looked and there is no number", which is a claim about their venue
 * contract. Same distinction, same reason, as the `headcount` member itself before
 * this endpoint existed.
 *
 * Public, because `web/` generates a TypeScript type from this shape. It lives in
 * `guest/` rather than in `wedding/` because that is where the fold over the ledger
 * happens, and the arrow between the two packages is already spent
 * (notes/2026-08-21-decision-the-headcount-endpoint.md §3).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class HeadcountResponse(
    val mealHeadcount: Int,
    val guaranteedHeadcount: Int?,
)
