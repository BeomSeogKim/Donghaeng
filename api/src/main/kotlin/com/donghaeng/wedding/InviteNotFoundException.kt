package com.donghaeng.wedding

import com.donghaeng.error.DomainException
import org.springframework.http.HttpStatus

/**
 * **One answer for every way a token can fail to be an invite**: not shaped like one,
 * an unknown selector, a selector that is ours with a verifier that is not, one that
 * was already spent, one whose seat or wedding is gone, and one that lost a race
 * between the read and the write.
 *
 * Telling them apart is exactly what a guesser would want: the selector is a public
 * handle, so a caller who could learn "this selector exists, only the verifier is
 * wrong" would have turned 128 bits of the token into a target worth grinding. So the
 * document is identical in every member.
 *
 * **Already spent stays here for a second reason** that survives even where the
 * guesser argument does not reach: that a link was used is a fact about the person who
 * used it, and the one asking is somebody else.
 *
 * **[InviteExpiredException] and [InviteSupersededException] are the two exceptions**,
 * and the one reason both are safe is written in the first of them.
 */
internal class InviteNotFoundException :
    DomainException(
        code = "INVITE_NOT_FOUND",
        status = HttpStatus.NOT_FOUND,
        detail = "This invite link is not usable.",
    )
