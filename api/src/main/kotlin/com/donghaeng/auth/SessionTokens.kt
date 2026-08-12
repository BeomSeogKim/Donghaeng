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
     * `null` for no cookie — and also for **more than one**, which is the
     * interesting case.
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
     * again. The session is unusable until the person clears cookies. That is a
     * denial of service, and it is the right trade against sitting silently
     * inside a stranger's ledger — but it is not "costs a re-login".
     *
     * The stronger
     * answer is the `__Host-` cookie prefix, which the browser itself refuses to
     * let a sibling set — but `__Host-` requires `Secure`, and dev serves
     * `http://localhost`, so it cannot be the mechanism in every environment.
     * Filed rather than half-adopted.
     */
    fun of(request: HttpServletRequest): SessionToken? {
        val values = request.cookies.orEmpty().filter { it.name == COOKIE_NAME }
        return SessionToken.parse(values.singleOrNull()?.value)
    }
}
