package com.donghaeng.auth.oauth

import com.donghaeng.config.FrontendProperties
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
 * **The browser goes back to the frontend, carrying a closed code in the fragment**
 * (notes/2026-08-13-decision-login-failure-return-path.md, `#109`). The callback is
 * a browser navigation to the API origin, so the problem+json this used to write
 * was a JSON document a person was left looking at with no way back — and refusing
 * consent is a normal path, not an error.
 *
 * Three properties of that redirect are the decision, not implementation detail:
 *
 * - **The fragment, never the query.** A fragment is not sent to a server, does not
 *   land in an access log and is not carried in `Referer`. `?error=` would be all
 *   three.
 * - **Two codes, and that is the whole vocabulary.** Nothing the provider wrote
 *   travels — not `error_description`, not the OAuth error code, not an exception
 *   message. It is text we did not author arriving at a URL an attacker chose, which
 *   is the same reason `#37` refused to publish it.
 * - **The origin is [FrontendProperties.baseUrl]**, the same configured value the
 *   success path redirects to, and never anything from the request.
 *
 * The problem+json below survives for the one environment that has no frontend
 * configured — production, until `#96`. It is not a second answer to the same
 * question; it is the answer when the first has nowhere to go.
 */
internal class OAuthLoginFailureHandler(
    private val objectMapper: ObjectMapper,
    private val frontend: FrontendProperties,
) : AuthenticationFailureHandler {
    private val logger = LogFactory.getLog(javaClass)

    override fun onAuthenticationFailure(
        request: HttpServletRequest,
        response: HttpServletResponse,
        exception: AuthenticationException,
    ) {
        val denied = (exception as? OAuth2AuthenticationException)?.error?.errorCode == ACCESS_DENIED
        // ONE LINE, AND NEVER A STACK TRACE. This path needs no cookie and no
        // credentials: `GET /login/oauth2/code/google?code=x&state=not-ours` reaches
        // it from anywhere, so logging the exception would hand an anonymous caller
        // unbounded WARN volume — the same amplification against the same 401/404/429
        // spike alerting that `SecurityConfig.unknownProviderIsNotFound` was written
        // to close, through a different door.
        //
        // What survives is what an incident actually needs: which of the two
        // outcomes it was, and OAuth's own error code — SANITISED, see
        // [errorCode]. `error_description` is not logged at all, for the same
        // reason it is not published.
        //
        // The rate-limit unit for a pre-authentication path has no answer yet — the
        // standing rule is per wedding and per link token, and this request has
        // neither. That is #98's, and it is load-bearing rather than tidy-up.
        if (denied) {
            logger.info("oauth login denied by the user")
        } else {
            logger.warn("oauth login failed: ${errorCode(exception)}")
        }

        // The redirect is the answer wherever there is a frontend to return to.
        // `check`ing instead, as the success path does, would turn an anonymous
        // GET into a 500 with a stack trace — the amplification named above.
        val origin = frontend.baseUrl.trimEnd('/')
        if (origin.isNotBlank()) {
            response.sendRedirect("$origin$LOGIN_ROUTE#$FRAGMENT_KEY=${if (denied) DENIED else FAILED}")
            return
        }

        val problem =
            ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, DETAIL).apply {
                // Through pathOrNull rather than URI.create: a request target
                // outside RFC 3986's grammar throws, and one Boot property
                // (`server.tomcat.relaxed-path-chars`) is all it takes for the
                // connector to stop keeping those off this line — the failure
                // MalformedPathContractTest exists about.
                instance = ProblemDocuments.pathOrNull(request.requestURI)
                setProperty(DomainException.CODE, if (denied) "OAUTH_LOGIN_DENIED" else "OAUTH_LOGIN_FAILED")
            }

        response.status = HttpStatus.UNAUTHORIZED.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        objectMapper.writeValue(response.outputStream, problem)
    }

    /**
     * **The one value on this line the caller writes, so it is allowlisted before
     * it is logged and not merely shortened.**
     *
     * `OAuth2AuthorizationResponseUtils.convert` takes the `error` query parameter
     * verbatim into `OAuth2Error.errorCode`, and the `state` precondition is no
     * obstacle — an attacker starts the authorization themselves and comes back to
     * their own callback. So `error=%0A2026-08-13%20INFO%20login%20succeeded`
     * forges a log line, against the one detection capability the security record
     * keeps (alerting on 401/404/429 spikes). Truncation bounds the length of that
     * forgery and nothing else.
     *
     * The allowlist loses nothing real: RFC 6749 §4.1.2.1 restricts an OAuth error
     * code to `%x20-21 / %x23-5B / %x5D-7E`, which already excludes CR and LF, and
     * every code either specification defines is lowercase ASCII with underscores.
     * A code that does not fit is a provider we would want to see reported as
     * unprintable rather than quoted.
     */
    private fun errorCode(exception: AuthenticationException): String {
        val raw =
            (exception as? OAuth2AuthenticationException)?.error?.errorCode
                ?: return exception.javaClass.simpleName
        val sanitised = raw.take(CODE_LOG_LIMIT).filter { it in SAFE_CODE_CHARACTERS }
        return sanitised.ifEmpty { "unprintable" }
    }

    private companion object {
        const val ACCESS_DENIED = "access_denied"
        const val CODE_LOG_LIMIT = 64

        const val DETAIL = "The OAuth login did not complete."

        /**
         * What an OAuth error code is actually made of; see [errorCode]. A `const`
         * rather than a `CharRange`, so it inlines and the companion gains no
         * accessor — this class is an entry point to `ArchitectureTest`, and an
         * entry point may not be read, not even by itself.
         */
        const val SAFE_CODE_CHARACTERS = "abcdefghijklmnopqrstuvwxyz0123456789_"

        /**
         * The frontend's login route, and the whole failure vocabulary. Adding a
         * third value is a decision, not a patch — it is the frontend that owns
         * every word the person reads, and it can only own the words for codes it
         * was told about.
         */
        const val LOGIN_ROUTE = "/login"
        const val FRAGMENT_KEY = "e"
        const val DENIED = "denied"
        const val FAILED = "failed"
    }
}
