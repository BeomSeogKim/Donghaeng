package com.donghaeng.auth.oauth

import com.donghaeng.config.FrontendProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error

/**
 * The half of `#109` the HTTP contract test cannot show: that the origin is
 * **configuration**, not a constant. Two environments, two destinations, one
 * handler — the dev value lives in `application-dev.yml` and production's arrives
 * from the deploy platform (`#96`), so a hardcoded string would pass every test
 * that only ever runs under `dev`.
 */
internal class OAuthLoginFailureHandlerTest {
    private val request = MockHttpServletRequest("GET", "/login/oauth2/code/google")

    @Test
    fun `the redirect target is whatever the environment configured`() {
        assertThat(locationAfterDenial(frontend = "http://localhost:3000"))
            .isEqualTo("http://localhost:3000/login#e=denied")
        assertThat(locationAfterDenial(frontend = "https://donghaeng.example"))
            .isEqualTo("https://donghaeng.example/login#e=denied")
        // A trailing slash is a configuration typo, not a second route.
        assertThat(locationAfterDenial(frontend = "https://donghaeng.example/"))
            .isEqualTo("https://donghaeng.example/login#e=denied")
    }

    @Test
    fun `everything that is not a refused consent is failed`() {
        assertThat(location(BadCredentialsException("state mismatch"), frontend = "https://donghaeng.example"))
            .isEqualTo("https://donghaeng.example/login#e=failed")
        assertThat(location(oauthError("server_error"), frontend = "https://donghaeng.example"))
            .isEqualTo("https://donghaeng.example/login#e=failed")
    }

    @Test
    fun `an environment with no frontend still answers, and answers problem+json`() {
        // Production is in exactly this state until `#96` decides the domain
        // (FrontendProperties). There is nowhere to send the browser, and a 500 on
        // an anonymous path is the log amplification this handler was written to
        // avoid — so the pre-#109 answer stands as the fallback.
        val response = MockHttpServletResponse()
        handler(frontend = "").onAuthenticationFailure(request, response, oauthError("access_denied"))

        assertThat(response.status).isEqualTo(401)
        assertThat(response.contentType).startsWith("application/problem+json")
        assertThat(response.contentAsString).contains("OAUTH_LOGIN_DENIED")
    }

    private fun locationAfterDenial(frontend: String) = location(oauthError("access_denied"), frontend)

    private fun location(
        exception: AuthenticationException,
        frontend: String,
    ): String {
        val response = MockHttpServletResponse()
        handler(frontend).onAuthenticationFailure(request, response, exception)
        assertThat(response.status).isEqualTo(302)
        return response.getHeader("Location") ?: error("no Location header")
    }

    private fun handler(frontend: String) = OAuthLoginFailureHandler(ObjectMapper(), FrontendProperties(baseUrl = frontend))

    private fun oauthError(code: String) = OAuth2AuthenticationException(OAuth2Error(code, "provider-authored text", null))
}
