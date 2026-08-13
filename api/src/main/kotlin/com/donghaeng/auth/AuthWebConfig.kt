package com.donghaeng.auth

import com.donghaeng.auth.session.AuthenticatedUser
import com.donghaeng.auth.session.CurrentUserArgumentResolver
import com.donghaeng.auth.session.SessionService
import org.springdoc.core.utils.SpringDocUtils
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Configuration
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Wires [CurrentUserArgumentResolver] into Spring MVC. Without this, a handler
 * declaring [AuthenticatedUser] fails to resolve its argument at runtime rather
 * than at startup — which is why the login tests assert a real request through a
 * real handler rather than calling the resolver.
 *
 * [ObjectProvider] rather than the service itself, and it is not a style choice:
 * `@WebMvcTest` includes every `WebMvcConfigurer` and excludes every `@Service`, so
 * an eager dependency here stops unrelated slices — the error-contract tests among
 * them — from building a context at all.
 */
@Configuration
internal class AuthWebConfig(
    private val sessions: ObjectProvider<SessionService>,
) : WebMvcConfigurer {
    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(CurrentUserArgumentResolver(sessions))
    }

    companion object {
        init {
            // Keeps [AuthenticatedUser] out of the generated OpenAPI document,
            // for every handler, whether or not it wrote `@CurrentUser`.
            //
            // springdoc treats an un-annotated complex parameter as a bag of query
            // parameters and expands its fields, so without this a handler that
            // omitted the annotation would publish `id` as a query parameter —
            // and `web/` would generate a client that SENDS a caller id. The
            // annotation carries `@Parameter(hidden = true)` and covers the same
            // ground, but only where someone remembered it; since the resolver
            // deliberately no longer requires the annotation, the document must
            // not require it either.
            SpringDocUtils.getConfig().addRequestWrapperToIgnore(AuthenticatedUser::class.java)
        }
    }
}
