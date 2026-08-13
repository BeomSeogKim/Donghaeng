package com.donghaeng.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Where a completed login sends the browser.
 *
 * A property class rather than the `@Value` this used to be, so that the three
 * `donghaeng.*` settings this package reads are read one way. The `@Value` was
 * threaded through the filter-chain bean into a handler constructor purely to
 * arrive at a string, which is a shape that stops being readable at the second
 * setting. (`SessionCookies` keeps its `@Value` on
 * `server.servlet.session.cookie.secure` — that is Boot's own namespace, and
 * binding one key out of it into a class of ours would be the second copy.)
 *
 * Blank is a legal, meaningful value: an environment with no frontend still boots
 * and still serves the API, and only a completed OAuth login fails. Production is
 * in exactly that state (`#96`).
 *
 * **The value is never taken from a request** — not a parameter, not a `Referer`,
 * not smuggled through `state`. See [com.donghaeng.auth.oauth.OAuthLoginSuccessHandler].
 */
@ConfigurationProperties("donghaeng.frontend")
internal data class FrontendProperties(
    val baseUrl: String = "",
)
