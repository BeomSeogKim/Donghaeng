package com.donghaeng.wedding

import com.donghaeng.auth.session.SessionService
import org.springdoc.core.utils.SpringDocUtils
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Configuration
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Wires [CurrentWeddingArgumentResolver] into Spring MVC.
 *
 * It is here rather than in `auth/`'s composition root, and that is not a
 * preference: `auth/` wiring naming a `wedding/` type would point an arrow from
 * `auth` to `wedding` while `wedding` already points at `auth.session`, and the
 * cycle rule in `ArchitectureTest` refuses the pair.
 *
 * Without this registration a handler declaring a [WeddingScope] fails to resolve
 * its argument at runtime rather than at startup — so the tests that hold `#5`
 * drive real requests through a real handler.
 */
@Configuration
internal class WeddingWebConfig(
    private val sessions: ObjectProvider<SessionService>,
    private val weddings: ObjectProvider<WeddingService>,
) : WebMvcConfigurer {
    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(CurrentWeddingArgumentResolver(sessions, weddings))
    }

    companion object {
        init {
            // Keeps [WeddingScope] out of the generated OpenAPI document, for every
            // handler, whether or not it wrote `@CurrentWedding` — the same
            // treatment `AuthenticatedUser` gets, and for a worse consequence.
            // springdoc expands an un-annotated complex parameter into query
            // parameters, so without this a wedding-scoped endpoint would publish
            // `id` and `callerId` as query parameters and `web/` would generate a
            // client that SENDS the wedding it wants to read.
            SpringDocUtils.getConfig().addRequestWrapperToIgnore(WeddingScope::class.java)
        }
    }
}
