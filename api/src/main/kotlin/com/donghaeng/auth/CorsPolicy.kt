package com.donghaeng.auth

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import java.time.Duration

/**
 * Which origins the browser may let call this API. The decision is `#6`'s and is
 * implemented here, not made here: **the base allows nothing and `dev` allows
 * `http://localhost:3000`.**
 *
 * A list of exact origins, and the two forbidden shapes are forbidden
 * structurally rather than by review:
 *
 * - **`*` is not merely discouraged, it is illegal here.** The session rides a
 *   cookie, so [CorsConfiguration.setAllowCredentials] is true, and the CORS spec
 *   refuses the wildcard alongside credentials — a browser rejects the pair
 *   outright. The failure would therefore be "nothing works", not "everything is
 *   exposed", but the value is still refused below so that nobody reaches for
 *   `allowedOriginPatterns` to make the wildcard work again.
 * - **`allowedOriginPatterns` is never used.** It is the API that admits a
 *   wildcard subdomain pattern and, written slightly wrong, matches
 *   `donghaeng.kr.evil.com` — an origin an attacker registers. Exact strings have
 *   no such failure mode, and with one frontend there is nothing a pattern buys.
 *   (The pattern itself is not written out here: a `://` followed by a star opens
 *   a NESTED block comment in Kotlin, which silently swallows the rest of a file.)
 */
@ConfigurationProperties("donghaeng.cors")
internal data class CorsProperties(
    /**
     * Empty means CORS is off and every cross-origin request is refused by the
     * browser. That is the correct value for an environment that has not said
     * otherwise, which is why the base file states it rather than omitting it.
     */
    val allowedOrigins: List<String> = emptyList(),
)

@Configuration
internal class CorsPolicy {
    @Bean
    fun corsConfigurationSource(properties: CorsProperties): CorsConfigurationSource {
        val configuration =
            CorsConfiguration().apply {
                allowedOrigins = properties.allowedOrigins
                // `web/` only ever issues these; a wider list is surface nobody asked
                // for. OPTIONS is handled by the CORS filter itself.
                allowedMethods = listOf("GET", "POST", "PATCH", "PUT", "DELETE")
                allowedHeaders = listOf("Content-Type", "Accept")
                // The whole point: without it the browser sends no cookie, and the
                // session cannot travel. It is also what makes `*` illegal above.
                allowCredentials = true
                maxAge = PREFLIGHT_CACHE.seconds
            }

        return UrlBasedCorsConfigurationSource().apply {
            // Only registered when an origin was configured. An empty list with
            // credentials on is a configuration a browser rejects for every
            // origin, and registering it would mean the deny case and a broken
            // case look identical in the response.
            if (properties.allowedOrigins.isNotEmpty()) registerCorsConfiguration("/**", configuration)
        }
    }

    private companion object {
        val PREFLIGHT_CACHE: Duration = Duration.ofMinutes(30)
    }
}
