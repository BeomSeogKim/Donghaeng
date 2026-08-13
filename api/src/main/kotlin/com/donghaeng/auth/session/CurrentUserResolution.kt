package com.donghaeng.auth.session

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
 * **`@CurrentUser` is documentation, not the match.** [CurrentUserArgumentResolver]
 * keys on the parameter TYPE alone, and the annotation is optional in every sense
 * that matters; see that class for why making it required was a vulnerability.
 * Write it anyway — it says at the call site what the parameter is — but nothing
 * depends on remembering it.
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
 * a build-time sweep of handler signatures.
 *
 * ## Why the match is on the TYPE and not on the annotation
 *
 * Requiring both was the first version, and it failed **open**, which is the one
 * direction this design may not fail in.
 *
 * Spring registers custom argument resolvers ahead of its own catch-all
 * `ServletModelAttributeMethodProcessor`, but only for parameters they claim. A
 * handler written `fun handle(caller: AuthenticatedUser)` with the annotation
 * forgotten was therefore not an error: this resolver declined it, the catch-all
 * took it, and Spring populated `AuthenticatedUser` from **request parameters** —
 * so `?id=42` arrived at the handler as a fully-formed caller identity. Not a
 * bypass of the session check but a replacement for it, chosen by the attacker.
 *
 * `#5`'s planned interceptor cannot catch that either: to it, such a handler HAS
 * declared a principal.
 *
 * ## What the type match does NOT close
 *
 * It closes the case above — the parameter written bare — and that is the case an
 * author reaches by FORGETTING something, which is why it was the urgent one.
 *
 * It does not make [AuthenticatedUser] unspellable in a weaker way. Spring adds
 * custom resolvers after its whole annotation- and type-based block, so
 * `@ModelAttribute caller: AuthenticatedUser`, `@RequestBody caller:
 * AuthenticatedUser`, and an [AuthenticatedUser]-typed property on a
 * `@RequestBody` class each win the parameter before this class is asked, and
 * [SessionService.resolve] is never called. Every one of those requires an author
 * to ADD an annotation rather than omit one, and `#5` is where the rest lives —
 * a build-time sweep of handler signatures, which is the only thing that can see
 * them, since a `HandlerInterceptor` sees a handler that has declared a principal
 * either way.
 */
internal class CurrentUserArgumentResolver(
    private val sessions: ObjectProvider<SessionService>,
) : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter): Boolean =
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
