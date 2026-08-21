package com.donghaeng.wedding

import com.donghaeng.auth.session.SessionService
import com.donghaeng.auth.session.SessionTokens
import com.donghaeng.auth.session.UnauthenticatedException
import com.donghaeng.error.DomainException
import com.donghaeng.error.ProblemDocuments
import io.swagger.v3.oas.annotations.Parameter
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.ObjectProvider
import org.springframework.core.MethodParameter
import org.springframework.http.HttpStatus
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.servlet.HandlerMapping

/** The `{weddingId}` this application maps every wedding-scoped path under. */
private const val WEDDING_ID_VARIABLE = "weddingId"

/**
 * The wedding this request is scoped to, and the caller it was resolved for.
 *
 * A handler that declares it cannot be reached anonymously, and cannot be reached
 * by a logged-in stranger: **resolution failing IS the rejection**
 * (notes/2026-08-10-decision-auth-gate-and-sequence.md), because
 * `authorizeHttpRequests` is `permitAll` in every environment and nothing in the
 * filter chain refuses anything.
 *
 * **`@CurrentWedding` is documentation, not the match.**
 * [CurrentWeddingArgumentResolver] keys on the parameter TYPE alone, for the reason
 * `#37` made [com.donghaeng.auth.session.CurrentUser] optional: a rule keyed on the
 * annotation misses precisely the handler that forgot it.
 */
@Parameter(hidden = true)
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class CurrentWedding

/**
 * [id] is the WEDDING's id and [callerId] the person's, so a handler that both
 * scopes a query and records who changed something needs one parameter rather than
 * two resolutions of the same session.
 *
 * It carries ids and not rows on purpose: `open-in-view` is false, so an entity
 * resolved here would be detached by the time a handler touched an association.
 */
data class WeddingScope(
    val id: Long,
    val callerId: Long,
)

/**
 * One answer for every way `user → seat → wedding` can fail to resolve: an id
 * that does not exist, a wedding the caller is not a member of, a wedding that has
 * been deleted, and an id that is not a number at all.
 *
 * **404 and never 403** (notes/2026-08-10-decision-cross-tenant-status-code.md).
 * 403 would say "this wedding exists and is not yours", which is a wedding-id
 * oracle for anyone holding a session; v1 has no roles, so it has no correct 403 at
 * all. The four cases share a `code` and a `detail` for the same reason they share
 * a status.
 */
internal class WeddingNotFoundException :
    DomainException(
        code = "WEDDING_NOT_FOUND",
        status = HttpStatus.NOT_FOUND,
        detail = "Wedding not found.",
    )

/**
 * `user → seat → wedding`, run before any handler that declares a
 * [WeddingScope] — **the gate**.
 *
 * It lives in `wedding/` rather than beside [com.donghaeng.auth.session.CurrentUser]
 * because `auth/` answers who is asking and this answers which wedding, which is a
 * question about `wedding_party` rows
 * (notes/2026-08-17-decision-first-domain-endpoint-shape.md). It reads them through
 * [WeddingService] and never through the repository: an argument resolver is an
 * inbound edge, and only a service may touch persistence.
 *
 * Three properties of the order below are load-bearing.
 *
 * **The session is resolved first**, so an anonymous request to any wedding-scoped
 * endpoint is 401 and never a 404 that tells a stranger which ids are absent.
 *
 * **The path variable is read here rather than declared by the handler.** A
 * `@PathVariable weddingId` is a wedding id the caller chose; it becomes trustworthy
 * only after this walk, so no handler is allowed to take one (swept by
 * `ResolvedPrincipalTest`). It follows that this class owns parsing it, and an id
 * that is not a number is answered exactly as an id nobody owns is.
 *
 * **A refusal is marked on the request.** The response deliberately cannot say
 * whether the wedding exists, so the log has to — that mark is the only input the
 * security record's alerting on 401/404 spikes has for telling a walk over the id
 * space apart from an ordinary 404 (notes/2026-08-10-decision-cross-tenant-status-code.md).
 * It is read by [com.donghaeng.error.GlobalErrorHandler] and never by anything that
 * writes a response.
 *
 * [ObjectProvider] for the same reason [com.donghaeng.auth.AuthWebConfig] uses one:
 * a `@WebMvcTest` builds every `WebMvcConfigurer` and no `@Service`, so an eager
 * dependency here would stop unrelated slices from building a context at all.
 */
internal class CurrentWeddingArgumentResolver(
    private val sessions: ObjectProvider<SessionService>,
    private val weddings: ObjectProvider<WeddingService>,
) : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter): Boolean = WeddingScope::class.java.isAssignableFrom(parameter.parameterType)

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): WeddingScope {
        val request =
            webRequest.getNativeRequest(HttpServletRequest::class.java)
                ?: throw UnauthenticatedException()
        val callerId =
            SessionTokens.of(request)?.let { sessions.getObject().resolve(it) }
                ?: throw UnauthenticatedException()

        val weddingId = weddingIdOf(request) ?: throw refuse(request)
        return weddings.getObject().scopeFor(callerId, weddingId) ?: throw refuse(request)
    }

    private fun refuse(request: HttpServletRequest): WeddingNotFoundException {
        request.setAttribute(ProblemDocuments.SCOPE_REFUSED, true)
        return WeddingNotFoundException()
    }

    /**
     * The `{weddingId}` Spring already matched, taken from the request rather than
     * from a handler parameter. `RequestMappingHandlerMapping` publishes the URI
     * template variables while it selects the handler, so they are in place before
     * any argument is resolved.
     */
    private fun weddingIdOf(request: HttpServletRequest): Long? {
        val variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) as? Map<*, *>
        return (variables?.get(WEDDING_ID_VARIABLE) as? String)?.toLongOrNull()
    }
}
