package com.donghaeng.config

import org.springframework.boot.context.properties.ConfigurationProperties

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
