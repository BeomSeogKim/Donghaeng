package com.donghaeng.auth

import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Configuration
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Wires [CurrentUserArgumentResolver] into Spring MVC. Without this, a handler
 * declaring `@CurrentUser` fails to resolve its argument at runtime rather than at
 * startup — which is why the login tests assert a real request through a real
 * handler rather than calling the resolver.
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
}
