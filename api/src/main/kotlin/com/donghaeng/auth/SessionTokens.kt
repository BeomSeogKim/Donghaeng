package com.donghaeng.auth

import jakarta.servlet.http.HttpServletRequest

/**
 * Where the session token is read from, as one named seam.
 *
 * The standing client rule is that lookup extracts a token **from the request**
 * rather than reading a cookie, so that a native couple app can carry the same
 * opaque token in a header without a redesign
 * (notes/2026-07-30-decision-client-strategy.md). This object is that seam. It
 * implements exactly one transport today, because exactly one client exists;
 * adding `Authorization: Bearer` is a branch here and nothing else, which is the
 * whole property the rule asks for.
 */
internal object SessionTokens {
    const val COOKIE_NAME = "DH_SESSION"

    /**
     * The one session this request is making a claim with — `null` for no cookie,
     * and also for **more than one**, which is the interesting case.
     *
     * A browser sends every cookie whose domain and path match, in an order the
     * RFC does not fix, and the server cannot tell which host set which. Our
     * cookie has no `Domain` attribute, but a sibling host under the same
     * registrable domain can set one WITH a `Domain` that covers us — and the
     * deployment shape invites exactly that: `web/` on Cloudflare Pages and this
     * API on a VPS, under one registrable domain. Taking the first match would let
     * that sibling seat the victim inside an attacker-held session, with every
     * later write landing in the attacker's ledger and nothing looking wrong to
     * either party.
     *
     * Refusing the ambiguous case closes it, and the cost is worse than a
     * re-login — say so plainly, because the mitigation is easy to overstate. The
     * planted cookie stays in the jar, so every later request still carries two
     * and this still returns `null`, including the request right after logging in
     * again. The session is unusable until the person clears cookies, or until
     * [all] is used to end every token they are carrying. That is a denial of
     * service, and it is the right trade against sitting silently inside a
     * stranger's ledger.
     *
     * The stronger answer is the `__Host-` cookie prefix, which the browser itself
     * refuses to let a sibling set — but `__Host-` requires `Secure`, and dev
     * serves `http://localhost`, so it cannot be the mechanism in every
     * environment. Filed rather than half-adopted.
     */
    fun of(request: HttpServletRequest): SessionToken? = present(request).singleOrNull()

    /**
     * EVERY well-formed token the request carries, for the one operation that must
     * be greedy: **revocation.**
     *
     * The asymmetry with [of] is deliberate and is the whole point of this
     * function, so do not "make them consistent"
     * (notes/2026-08-12-decision-session-cookie-ambiguity.md).
     *
     * Reading is strict because acting on the wrong one of two tokens means acting
     * as the wrong person. Revoking is greedy because the failure runs the other
     * way, and it converts the denial of service above into the takeover it was
     * supposed to prevent: with [of], a logout carrying two cookies revoked
     * nothing and then cleared ours — the host-only one, the only one a `Set-Cookie`
     * without a `Domain` can delete — leaving the planted cookie alone in the jar
     * and **valid**. The next request resolved cleanly, as the attacker. The
     * victim's own row was never revoked either, and lived out its 180 days.
     *
     * Ending every token the browser presents costs nothing that is ours to lose:
     * a person who asks to be logged out is not asking to keep one of the
     * sessions on this device, and a token that is not theirs still has to pass
     * the constant-time verifier check in [SessionService.revoke] before anything
     * happens to it.
     */
    fun all(request: HttpServletRequest): List<SessionToken> = present(request)

    private fun present(request: HttpServletRequest): List<SessionToken> =
        request.cookies
            .orEmpty()
            .filter { it.name == COOKIE_NAME }
            .mapNotNull { SessionToken.parse(it.value) }
}
