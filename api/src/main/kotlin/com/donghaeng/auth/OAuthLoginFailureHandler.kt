package com.donghaeng.auth

import com.donghaeng.error.DomainException
import com.donghaeng.error.ProblemDocuments
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.apache.commons.logging.LogFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.security.core.AuthenticationException
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.web.authentication.AuthenticationFailureHandler

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
