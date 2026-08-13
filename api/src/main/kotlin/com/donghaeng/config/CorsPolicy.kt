package com.donghaeng.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import java.time.Duration

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
        /**
         * How long a browser may skip the preflight for a given request shape.
         *
         * The trade is latency against how long a policy change takes to reach a
         * page that is already open: every non-simple request pays an extra round
         * trip when this expires, and a shortened origin list keeps being honoured
         * by open tabs until it does. Thirty minutes is short enough that a
         * mistake is corrected within one working session and long enough that a
         * burst of edits pays the preflight once.
         *
         * It is deliberately not the browser's maximum (Chromium caps this at two
         * hours regardless): the cache is the only part of this policy we cannot
         * revoke, so it is the one number to keep small.
         */
        val PREFLIGHT_CACHE: Duration = Duration.ofMinutes(30)
    }
}
