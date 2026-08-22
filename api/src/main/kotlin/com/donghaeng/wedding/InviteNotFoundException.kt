package com.donghaeng.wedding

import com.donghaeng.error.DomainException
import org.springframework.http.HttpStatus

/**
 * **One answer for every way a token can fail to be an invite**: not shaped like one,
 * an unknown selector, a selector that is ours with a verifier that is not, one that
 * was already spent, one that 재발급 replaced, and one whose seat or wedding is gone.
 *
 * Telling them apart is exactly what a guesser would want: the selector is a public
 * handle, so a caller who could learn "this selector exists, only the verifier is
 * wrong" would have turned 128 bits of the token into a target worth grinding. So the
 * document is identical in every member.
 *
 * **[InviteExpiredException] is the one exception to that**, and the reason it is safe
 * is written there.
 */
internal class InviteNotFoundException :
    DomainException(
        code = "INVITE_NOT_FOUND",
        status = HttpStatus.NOT_FOUND,
        detail = "This invite link is not usable.",
    )
