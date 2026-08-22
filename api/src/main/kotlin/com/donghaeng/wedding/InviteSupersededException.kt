package com.donghaeng.wedding

import com.donghaeng.error.DomainException
import org.springframework.http.HttpStatus

/**
 * 재발급이 밀어낸 링크 (notes/2026-08-22-decision-the-superseded-link-speaks.md).
 *
 * **The second refusal told apart from [InviteNotFoundException]**, and it stands on
 * [InviteExpiredException]'s argument unmodified. Safe: `WeddingInviteService.accept`
 * only asks how the invite died *after* the presented verifier matched, so this is
 * said to somebody holding a token that really was ours and a guesser learns nothing.
 * Necessary: 발급 is 재발급, and against a one-day life reissuing is an ordinary daily
 * act — so a superseded link sitting in a chat room is an ordinary daily state, and
 * the person holding it needs to be told that the working link is on their partner's
 * phone rather than that there is nothing here.
 *
 * **`acceptedAt` is not this**, even on a row that also carries `revokedAt`: that a
 * link was already spent is a fact about the partner who spent it, not about the
 * token, so it stays [InviteNotFoundException].
 *
 * 404 and not 410, for the reason written in [InviteExpiredException].
 */
internal class InviteSupersededException :
    DomainException(
        code = "INVITE_SUPERSEDED",
        status = HttpStatus.NOT_FOUND,
        detail = "This invite link has been replaced by a newer one.",
    )
