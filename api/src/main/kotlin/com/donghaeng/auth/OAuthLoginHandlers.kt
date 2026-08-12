package com.donghaeng.auth

import com.donghaeng.error.DomainException
import com.donghaeng.error.ProblemDocuments
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.apache.commons.logging.LogFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.security.core.Authentication
import org.springframework.security.core.AuthenticationException
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.web.authentication.AuthenticationFailureHandler
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

        val issued = logins.login(GoogleProfile.of(principal), SessionTokens.of(request))
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.issue(issued).toString())

        // The servlet session existed only to carry the authorization request —
        // `state`, the PKCE verifier, the nonce — across the round trip. Ending it
        // here means the browser leaves holding exactly one credential, ours,
        // rather than also holding a JSESSIONID with a SecurityContext behind it
        // that nothing in this application reads.
        request.getSession(false)?.invalidate()

        response.sendRedirect(frontendBaseUrl)
    }
}

/**
 * The failure half, and the reason it is written at all: an exception raised by
 * the OAuth2 login filter never reaches `@RestControllerAdvice` — a filter is
 * outside Spring MVC's exception resolvers entirely
 * (notes/2026-08-10-decision-auth-gate-and-sequence.md, and issue #62). Spring's
 * default handler answers with a redirect to a generated HTML login page, which
 * this API does not have and would not serve.
 *
 * So the document is written directly, in the same shape everything else in this
 * API produces: `application/problem+json` with a stable [DomainException.CODE].
 *
 * **What it publishes is fixed text, never the provider's.** `error_description`
 * is authored by Google and would be attacker-influencable in the general case;
 * the client gets one of two codes and the server log gets the exception.
 */
internal class OAuthLoginFailureHandler(
    private val objectMapper: ObjectMapper,
) : AuthenticationFailureHandler {
    private val logger = LogFactory.getLog(javaClass)

    override fun onAuthenticationFailure(
        request: HttpServletRequest,
        response: HttpServletResponse,
        exception: AuthenticationException,
    ) {
        val denied = (exception as? OAuth2AuthenticationException)?.error?.errorCode == ACCESS_DENIED
        val code = if (denied) "OAUTH_LOGIN_DENIED" else "OAUTH_LOGIN_FAILED"

        // A refused consent is the user's decision, not an incident; everything
        // else — a `state` mismatch, a token exchange that failed, an ID token
        // that did not validate — is worth a line, because it is also what an
        // attack against the callback looks like.
        if (denied) logger.info("oauth login denied by the user") else logger.warn("oauth login failed", exception)

        val problem =
            ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, DETAIL).apply {
                // Through pathOrNull rather than URI.create: a request target
                // outside RFC 3986's grammar throws, and one Boot property
                // (`server.tomcat.relaxed-path-chars`) is all it takes for the
                // connector to stop keeping those off this line — the failure
                // MalformedPathContractTest exists about.
                instance = ProblemDocuments.pathOrNull(request.requestURI)
                setProperty(DomainException.CODE, code)
            }

        response.status = HttpStatus.UNAUTHORIZED.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        objectMapper.writeValue(response.outputStream, problem)
    }

    private companion object {
        const val ACCESS_DENIED = "access_denied"
        const val DETAIL = "The OAuth login did not complete."
    }
}
