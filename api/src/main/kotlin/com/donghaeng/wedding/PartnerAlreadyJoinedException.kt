package com.donghaeng.wedding

import com.donghaeng.error.DomainException
import org.springframework.http.HttpStatus

/**
 * The seat this request is about already has somebody in it — so there is nobody left
 * to invite, and nothing left to accept.
 *
 * **409 rather than the 404 a cross-tenant refusal answers**, for the reason
 * [AlreadyInAWeddingException] is: that 404 exists to deny a wedding-id oracle, and
 * both callers who can reach this one have already been let past it. The issuer holds a
 * seat in the wedding they are asking about; the accepter presented a token that was
 * ours. Neither learns anything about a wedding they have no claim on, and both are
 * being told the same fact — 두 자리가 다 찼다.
 *
 * A wedding whose second seat is taken has no 재발급 screen at all, so `web/` should
 * rarely produce this; it is what a stale tab and a `curl` get.
 */
internal class PartnerAlreadyJoinedException :
    DomainException(
        code = "PARTNER_ALREADY_JOINED",
        status = HttpStatus.CONFLICT,
        detail = "Both seats in this wedding are taken.",
    )
