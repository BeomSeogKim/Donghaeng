package com.donghaeng.wedding

import com.donghaeng.error.DomainException
import org.springframework.http.HttpStatus

/**
 * The link was ours and is a day old (notes/2026-08-22-decision-the-invite-link.md §1).
 *
 * **The only refusal told apart from [InviteNotFoundException]**, and the two arguments
 * for it point the same way. It is safe: this is only ever said to someone presenting a
 * token that really was ours, verifier and all, so a guesser is told INVITE_NOT_FOUND
 * exactly as they are told for nonsense and learns nothing. And it is necessary: the
 * founder's one-day life is affordable *because* 재발급 is one tap away, which is
 * advice the screen can only give if the API says which failure this was.
 *
 * 404 and not 410. The status table in `docs/api-spec.md` has one row for "the thing
 * you named is not there", and inventing a second status for a shade of it would make
 * `web/` branch on the status instead of on `code`.
 */
internal class InviteExpiredException :
    DomainException(
        code = "INVITE_EXPIRED",
        status = HttpStatus.NOT_FOUND,
        detail = "This invite link has expired.",
    )
