package com.donghaeng.auth.oauth

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

        // ONE LINE, AND NEVER A STACK TRACE. This path needs no cookie and no
        // credentials: `GET /login/oauth2/code/google?code=x&state=not-ours` reaches
        // it from anywhere, so logging the exception would hand an anonymous caller
        // unbounded WARN volume — the same amplification against the same 401/404/429
        // spike alerting that `SecurityConfig.unknownProviderIsNotFound` was written
        // to close, through a different door.
        //
        // What survives is what an incident actually needs: which of the two
        // outcomes it was, and OAuth's own error code. The code is a short fixed
        // vocabulary; `error_description` is authored by the provider and is not
        // logged, for the same reason it is not published.
        //
        // The rate-limit unit for a pre-authentication path has no answer yet — the
        // standing rule is per wedding and per link token, and this request has
        // neither. That is #98's, and it is load-bearing rather than tidy-up.
        if (denied) {
            logger.info("oauth login denied by the user")
        } else {
            logger.warn("oauth login failed: ${errorCode(exception)}")
        }

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

    /** Truncated: the code is short by specification, and a provider is not a trusted author of length. */
    private fun errorCode(exception: AuthenticationException): String =
        (exception as? OAuth2AuthenticationException)?.error?.errorCode?.take(CODE_LOG_LIMIT)
            ?: exception.javaClass.simpleName

    private companion object {
        const val ACCESS_DENIED = "access_denied"
        const val CODE_LOG_LIMIT = 64
        const val DETAIL = "The OAuth login did not complete."
    }
}
