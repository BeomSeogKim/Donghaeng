package com.donghaeng.wedding

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

/**
 * A freshly minted invite — public, because `web/` generates a TypeScript type from
 * this shape.
 *
 * **[token] is published exactly once and can never be read back.** Only its hash is
 * stored, so reopening 설정 cannot re-display a link that was issued yesterday; the
 * screen's only affordance there is 재발급, which mints a new one and kills the old
 * (notes/2026-08-22-decision-the-partner-invite.md §5). This is the single most
 * load-bearing fact for `#182`.
 *
 * **No URL, deliberately.** The API does not know the frontend's origin — the one place
 * that is configured is the OAuth redirect, and it exists because a browser has to be
 * sent somewhere. More to the point, the link's shape is a frontend decision the record
 * already made: the token goes in the **fragment** (`/invite#t=<token>`), which is the
 * only part of a URL that never reaches any server. An API that assembled the link
 * would be an API that could get that wrong.
 *
 * **No id, no seat, no issuer.** Nothing renders them, and every member of this
 * response is a member the frontend may end up logging.
 */
data class IssuedInviteResponse(
    @param:Schema(description = "The invite token. Shown once, never retrievable — put it in the link's fragment")
    val token: String,
    @param:Schema(description = "When the link stops working — at most one day out", example = "2026-08-23T09:00:00Z")
    val expiresAt: Instant,
) {
    /**
     * Masked for the reason [JoinWeddingRequest] is, on the outbound leg: Spring MVC logs
     * the value it is about to write at DEBUG (`AbstractMessageConverterMethodProcessor`,
     * `Writing [...]`), and a generated `IssuedInviteResponse(token=…)` is 93 characters —
     * inside the 100-character truncation window, so the whole live credential would be
     * logged.
     *
     * This is the ONE object in the application that holds a usable invite token outside
     * [InviteToken] itself, which is exactly why the masking has to follow it here.
     */
    override fun toString(): String = "IssuedInviteResponse(token=***, expiresAt=$expiresAt)"
}
