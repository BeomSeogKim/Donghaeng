package com.donghaeng.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * The cookie's flags **as they reach the wire**, and `Secure` is why this file
 * exists.
 *
 * Everything else about that flag is asserted in its off state: the login contract
 * test runs the `dev` profile and checks the header does NOT carry it, and
 * `ProfileConfigurationTest` reads the yml. Neither observes [SessionCookies]
 * actually writing it, so an implementation that ignored the setting would pass
 * both — on the one flag whose wrong value puts a live session token on the wire
 * in plaintext.
 */
internal class SessionCookiesTest {
    private val properties = SessionProperties(idle = Duration.ofDays(14), absolute = Duration.ofDays(90))

    @Test
    fun `a secure environment writes Secure onto the cookie`() {
        val header = SessionCookies(secure = true, properties = properties).issue(SessionToken.mint()).toString()

        assertThat(header).contains("Secure")
    }

    @Test
    fun `the flags that never vary are written whatever the environment says`() {
        listOf(true, false).forEach { secure ->
            val header = SessionCookies(secure = secure, properties = properties).issue(SessionToken.mint()).toString()

            assertThat(header).describedAs("HttpOnly, secure=%s", secure).contains("HttpOnly")
            // Lax and never Strict: the OAuth callback is a top-level cross-site
            // navigation, so Strict withholds the cookie at the moment of login.
            assertThat(header).describedAs("SameSite, secure=%s", secure).contains("SameSite=Lax")
            assertThat(header).describedAs("Path, secure=%s", secure).contains("Path=/")
            // No Domain: never widened to siblings of this host.
            assertThat(header).describedAs("Domain, secure=%s", secure).doesNotContain("Domain=")
        }
    }

    @Test
    fun `Max-Age is the absolute lifetime, not the idle one`() {
        // A cookie expiring 14 days after issuance would sign out a couple who
        // used the app yesterday. Expiry is decided by SessionService against the
        // row; this is only the browser's copy.
        val header = SessionCookies(secure = true, properties = properties).issue(SessionToken.mint()).toString()

        assertThat(header).contains("Max-Age=${Duration.ofDays(90).seconds}")
    }
}
