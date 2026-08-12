package com.donghaeng.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.web.authentication.AuthenticationSuccessHandler

/**
 * What happens after Spring Security has verified the authorization code, the
 * `state`, the PKCE verifier and the ID token: the person becomes an `app_user`,
 * a session is issued, and the browser is sent to the frontend.
 *
 * **[frontendBaseUrl] is configuration and never comes from the request** — not
 * from a parameter, not from a `Referer`, and not smuggled through `state`. Any of
 * those is an open redirect, and an open redirect on the login callback hands an
 * attacker a page that has just been given a valid session. Spring's own default
 * handler is `SavedRequestAwareAuthenticationSuccessHandler`, which redirects to
 * whatever request was cached before login; replacing it is the reason this class
 * exists, and `SecurityConfig` disables the request cache as well so there is
 * nothing cached to honour.
 */
internal class OAuthLoginSuccessHandler(
    private val logins: LoginService,
    private val cookies: SessionCookies,
    private val frontendBaseUrl: String,
) : AuthenticationSuccessHandler {
    override fun onAuthenticationSuccess(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication,
    ) {
        // Not a defensive check on a supported case: `oauth2Login` with an
        // `openid` scope always produces an OidcUser, so anything else means the
        // registration was rebuilt without `openid` — and with it goes the whole
        // ID-token validation. Failing loudly beats logging someone in on an
        // unvalidated userinfo response.
        val principal =
            authentication.principal as? OidcUser
                ?: error("OAuth login produced ${authentication.principal?.javaClass?.name}, not an OidcUser")

        // The frontend origin is only needed on the one path that redirects a
        // browser, so an environment that has not configured it still boots and
        // still serves the API. Checked here rather than at startup for that
        // reason.
        check(frontendBaseUrl.isNotBlank()) {
            "donghaeng.frontend.base-url is not configured, so a completed login has nowhere to land"
        }

        val issued = logins.login(profileOf(authentication, principal), SessionTokens.of(request))
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.issue(issued).toString())

        // The servlet session existed only to carry the authorization request —
        // `state`, the PKCE verifier, the nonce — across the round trip. Ending it
        // here means the browser leaves holding exactly one credential, ours,
        // rather than also holding a JSESSIONID with a SecurityContext behind it
        // that nothing in this application reads.
        request.getSession(false)?.invalidate()

        response.sendRedirect(frontendBaseUrl)
    }

    /**
     * Dispatched on the registration id the request actually came back through,
     * never on a constant. With one provider the two are the same string; at `#89`
     * they are not, and picking the wrong one writes a Kakao subject under
     * `provider = 'GOOGLE'` — a row no constraint objects to and no test would
     * notice until someone's ledger goes missing. See [ProviderProfile].
     */
    private fun profileOf(
        authentication: Authentication,
        principal: OidcUser,
    ): ProviderProfile =
        when (val registrationId = (authentication as? OAuth2AuthenticationToken)?.authorizedClientRegistrationId) {
            GoogleClientRegistration.REGISTRATION_ID -> GoogleProfile.of(principal)
            else -> error("no profile mapping for OAuth registration '$registrationId'")
        }
}
