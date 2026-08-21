package com.donghaeng.wedding

import com.donghaeng.error.DomainException
import org.springframework.http.HttpStatus

/**
 * 한 사람은 웨딩 하나 — the caller already belongs to one, so they may not create or
 * join another (notes/2026-08-21-decision-one-wedding-per-person.md).
 *
 * **409 rather than the 404 every cross-tenant refusal answers**
 * (notes/2026-08-10-decision-cross-tenant-status-code.md), and the difference is not
 * a judgement call about which reads better: that 404 exists to deny a wedding-id
 * oracle, and there is no oracle here. This says nothing about any wedding — it is a
 * fact about **the caller's own account**, which they already know and which
 * `GET /weddings` answers in full to that same session. A 404 would hide nothing and
 * would tell `web/` "the thing you asked about is gone" when the truth is "you
 * already have one, go and open it".
 *
 * The `code` names the caller's STATE rather than this endpoint's outcome, because
 * `#9`'s invite accept refuses on exactly the same fact and must answer with exactly
 * the same word.
 */
internal class AlreadyInAWeddingException :
    DomainException(
        code = "ALREADY_IN_A_WEDDING",
        status = HttpStatus.CONFLICT,
        detail = "This account already belongs to a wedding.",
    )
