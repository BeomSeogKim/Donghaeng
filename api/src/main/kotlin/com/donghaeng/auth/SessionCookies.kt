package com.donghaeng.auth

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Component

/**
 * Builds the cookie the session travels in.
 *
 * Three of the four flags are written here and not in a configuration file,
 * because their wrong value is a vulnerability and a fixed value cannot be
 * loosened by an environment variable:
 *
 * - **`HttpOnly`** — the token must be unreadable to script.
 * - **`SameSite=Lax`**, and NOT `Strict`: the OAuth callback is a top-level
 *   cross-site navigation, so `Strict` withholds the cookie at exactly the moment
 *   of login. `Lax` is also half of v1's CSRF answer — see [SecurityConfig].
 * - **`Path=/` with no `Domain`**, so the cookie is never widened to siblings of
 *   this host.
 *
 * `Secure` is the one that genuinely varies, and it is read from
 * `server.servlet.session.cookie.secure` — pinned `true` in the base file and
 * loosened only by `dev`, which serves `http://localhost`. That direction is the
 * point: Boot defaults the property to `false`, so a base that did not pin it
 * would make production the profile that has to remember.
 */
@Component
internal class SessionCookies(
    @param:Value("\${server.servlet.session.cookie.secure}") private val secure: Boolean,
    private val properties: SessionProperties,
) {
    /**
     * `Max-Age` is the absolute lifetime, never the idle one: a cookie that
     * expired 14 days after issuance would log out a couple who used the app
     * yesterday. It is a convenience for the browser in any case — expiry is
     * decided by [SessionService.resolve] against the row, and a client that keeps
     * a stale cookie forever gains nothing by it.
     */
    fun issue(token: SessionToken): ResponseCookie =
        ResponseCookie
            .from(SessionTokens.COOKIE_NAME, token.cookieValue)
            .httpOnly(true)
            .secure(secure)
            .sameSite("Lax")
            .path("/")
            .maxAge(properties.absolute)
            .build()
}
