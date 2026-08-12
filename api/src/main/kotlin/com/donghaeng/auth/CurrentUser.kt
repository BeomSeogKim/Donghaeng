package com.donghaeng.auth

import com.donghaeng.error.DomainException
import io.swagger.v3.oas.annotations.Parameter
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.ObjectProvider
import org.springframework.core.MethodParameter
import org.springframework.http.HttpStatus
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

/**
 * The person making this request. A handler that declares it cannot be reached
 * anonymously — resolution failing IS the rejection
 * (notes/2026-08-10-decision-auth-gate-and-sequence.md).
 *
 * Annotate with `@Parameter(hidden = true)` wherever it appears, or springdoc
 * documents it as a query parameter and `web/` generates a client that sends one.
 */
@Parameter(hidden = true)
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class CurrentUser

/** Deliberately just the id: nothing else has been proved about the caller. */
data class AuthenticatedUser(
    val id: Long,
)

internal class UnauthenticatedException :
    DomainException(
        code = "UNAUTHENTICATED",
        status = HttpStatus.UNAUTHORIZED,
        detail = "A valid session is required.",
    )

/**
 * Turns the request's session token into an [AuthenticatedUser], and throws
 * [UnauthenticatedException] when it cannot.
 *
 * An argument resolver rather than a filter, because the parameter is the point:
 * `authorizeHttpRequests` stays `permitAll` in every environment, so what rejects
 * an anonymous request is a handler having *declared* that it needs a caller. The
 * shape is chosen so `#5` can still close the hole it leaves — a handler that
 * declares nothing is open — with either an interceptor over declared handlers or
 * a build-time sweep of handler signatures. Both read this annotation.
 */
internal class CurrentUserArgumentResolver(
    private val sessions: ObjectProvider<SessionService>,
) : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.hasParameterAnnotation(CurrentUser::class.java) &&
            AuthenticatedUser::class.java.isAssignableFrom(parameter.parameterType)

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): AuthenticatedUser {
        val request =
            webRequest.getNativeRequest(HttpServletRequest::class.java)
                ?: throw UnauthenticatedException()
        val token = SessionTokens.of(request) ?: throw UnauthenticatedException()
        val userId = sessions.getObject().resolve(token) ?: throw UnauthenticatedException()
        return AuthenticatedUser(userId)
    }
}

/**
 * Where the session token is read from, as one named seam.
 *
 * The standing client rule is that lookup extracts a token **from the request**
 * rather than reading a cookie, so that a native couple app can carry the same
 * opaque token in a header without a redesign
 * (notes/2026-07-30-decision-client-strategy.md). This function is that seam. It
 * implements exactly one transport today, because exactly one client exists;
 * adding `Authorization: Bearer` is a branch here and nothing else, which is the
 * whole property the rule asks for.
 */
internal object SessionTokens {
    const val COOKIE_NAME = "DH_SESSION"

    fun of(request: HttpServletRequest): SessionToken? = SessionToken.parse(request.cookies?.firstOrNull { it.name == COOKIE_NAME }?.value)
}
