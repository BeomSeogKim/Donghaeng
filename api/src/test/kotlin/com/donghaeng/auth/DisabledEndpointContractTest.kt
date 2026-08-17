package com.donghaeng.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * The endpoints `SecurityConfig` refuses to register, asserted to be absent.
 *
 * **`RequestContentTypeTest` can never see these**, and that is the point of this
 * file rather than a caveat to it. Spring Security registers `/login` and `/logout`
 * from inside the filter chain, not as `@RequestMapping` handlers, so no sweep over
 * annotated methods will ever find them — they are structurally invisible to the
 * check that holds the rest of the content-type gate.
 *
 * What stands between us and them is `logout { disable() }` and
 * `formLogin { disable() }`: two lines, each deletable in one keystroke, each of
 * which re-registers precisely the shape the gate exists to forbid.
 *
 * - **`LogoutConfigurer` stops narrowing its matcher to POST when CSRF is
 *   disabled**, which it is here, so a re-enabled `/logout` answers GET as well —
 *   a state-changing GET, the other half of the standing pair, reachable from an
 *   `<img src>`.
 * - **`formLogin` registers `POST /login` accepting
 *   `application/x-www-form-urlencoded`**, which is the canonical CORS-simple state
 *   changer: no preflight, cookie attached, and a login form is a credential path.
 *
 * Neither is hypothetical enough to leave to prose — `#5` will be editing this
 * filter chain.
 *
 * **The two lines are not equally load-bearing, and the mutations say so.** Deleting
 * `logout { disable() }` re-creates the endpoint immediately and this test goes red
 * on a 302, because `HttpSecurityConfiguration` applies `logout(withDefaults())` to
 * every chain. Deleting `formLogin { disable() }` creates nothing and this test
 * stays green — Spring Security 6 does *not* enable form login by default, so that
 * line is defensive rather than load-bearing. Writing `formLogin { }` does create
 * `POST /login`, and that is caught. **This test therefore asserts the surface, not
 * the two lines**, which is the stronger of the two things to assert: it holds
 * whichever way a future edit reaches for them.
 *
 * Named a contract test rather than `*ProfileBootTest` deliberately: the subject is
 * the routing surface, which is the same under every profile, and `*ProfileBootTest`
 * is reserved for tests whose subject is the resolved configuration (`api/AGENTS.md`,
 * Architecture). It boots the application for the same reason
 * `MalformedPathContractTest` does.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Import(StubGoogleRegistration::class)
internal class DisabledEndpointContractTest : GoogleLoginFixture() {
    @Test
    fun `Spring Security's own logout endpoint does not exist, by either method`() {
        // GET is the one that matters — with `csrf { disable() }` above it in the
        // chain, a re-enabled LogoutConfigurer answers GET too.
        assertThat(statusOf("GET", "/logout")).isEqualTo(404)
        assertThat(statusOf("POST", "/logout", "application/json")).isEqualTo(404)
    }

    @Test
    fun `Spring Security's own login form does not exist`() {
        // Sent form-encoded, which is how the real thing would be posted to and is
        // exactly the CORS-simple shape the gate forbids everywhere else.
        assertThat(statusOf("POST", "/login", "application/x-www-form-urlencoded")).isEqualTo(404)
        assertThat(statusOf("GET", "/login")).isEqualTo(404)
    }

    private fun statusOf(
        method: String,
        path: String,
        contentType: String? = null,
    ): Int {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .apply { if (contentType != null) header("Content-Type", contentType) }
                .build()
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode()
    }
}
