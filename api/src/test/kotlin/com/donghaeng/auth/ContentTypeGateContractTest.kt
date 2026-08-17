package com.donghaeng.auth

import com.donghaeng.ApiFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.net.HttpCookie
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * The observed half of v1's CSRF gate
 * (`notes/2026-08-13-decision-static-front-and-content-type-gate.md`).
 *
 * `RequestContentTypeTest` asserts that every state-changing handler *declares*
 * `consumes = application/json`. This asserts what that declaration actually does
 * to a request, over real HTTP against the real endpoint, because the whole rule
 * rests on a property of Spring that is worth observing rather than assuming:
 * **`consumes` is a mapping condition, not a message-converter concern.** It is
 * evaluated by `ConsumesRequestCondition` while the handler is being selected, so a
 * request that does not satisfy it never enters the method — which is why this
 * closes the hole even on a handler like `logout` that reads no body at all.
 *
 * The case that had to be checked rather than reasoned about is **no `Content-Type`
 * header**. A cross-origin `fetch` with no body sends none, and that is a CORS
 * *simple* request — it skips the preflight, so if it matched the mapping the gate
 * would be open on exactly the requests it exists to stop. `ConsumesRequestCondition`
 * has a shortcut for a request with no body, and it does not apply here: it is
 * guarded by `bodyRequired`, which `RequestMappingHandlerMapping` lowers only for a
 * `@RequestBody(required = false)` parameter, and `logout` has no `@RequestBody` at
 * all. Observed, not read off the source: it is 415.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@Import(StubGoogleRegistration::class)
internal class ContentTypeGateContractTest : ApiFixture() {
    @Test
    fun `a POST carrying a safelisted content type never reaches the handler`() {
        // The attack, as close as a test gets to it: a live session, and a request
        // shaped exactly like one a sibling host could send with no preflight.
        val session = login()

        val refused = postRaw("/auth/logout", session, contentType = "text/plain")

        assertThat(refused.statusCode()).isEqualTo(415)
        // The session SURVIVED, which is the assertion that matters. A 415 with a
        // revoked session would mean the handler ran and only the response was
        // refused — the gate has to stop the request, not the reply.
        assertThat(get("/auth/me", listOf(session)).statusCode()).isEqualTo(200)
    }

    @Test
    fun `a POST with no content type at all is refused the same way`() {
        // A cross-origin fetch with no body sends no Content-Type, and this is the
        // one case the mechanism could plausibly have fallen the other way on.
        val session = login()

        val refused = postRaw("/auth/logout", session, contentType = null)

        assertThat(refused.statusCode()).isEqualTo(415)
        assertThat(get("/auth/me", listOf(session)).statusCode()).isEqualTo(200)
    }

    @Test
    fun `the same request under application json is the logout it claims to be`() {
        // The control. Without it the two assertions above would pass just as well
        // against an endpoint that had stopped working altogether.
        val session = login()

        assertThat(postRaw("/auth/logout", session, contentType = "application/json").statusCode()).isEqualTo(204)
        assertThat(get("/auth/me", listOf(session)).statusCode()).isEqualTo(401)
    }

    @Test
    fun `the refusal is a problem document, and web can match it`() {
        // docs/api-spec.md promises this for every error the application produces,
        // and a 415 raised during handler selection is the kind that could quietly
        // escape it — it is raised before the handler exists.
        //
        // The `code` is asserted because `web/`'s test double has to return the same
        // shape the real server does. A double that answers a bare 415 with no body
        // is the exact drift this pins shut, so the spec now publishes this document
        // and this test is what keeps the spec honest.
        val refused = postRaw("/auth/logout", session = null, contentType = "text/plain")

        assertThat(refused.statusCode()).isEqualTo(415)
        assertThat(refused.headers().firstValue("Content-Type").orElse(""))
            .startsWith("application/problem+json")
        assertThat(refused.json().get("code").asText()).isEqualTo("UNSUPPORTED_MEDIA_TYPE")
        assertThat(refused.json().get("instance").asText()).isEqualTo("/auth/logout")

        // Three refusal shapes reach this status, and all three answer the same
        // `code` so `web/` branches once. Only `detail` differs, which is one more
        // reason it is never rendered: the first variant quotes the submitted header
        // straight back.
        //
        //   text/plain  -> "Content-Type 'text/plain' is not supported."
        //   no header   -> "Content-Type 'null' is not supported."
        //   unparseable -> "Could not parse Content-Type."
        //
        // The third arrives through a different ProblemDetail constructor and was
        // missed by the spec until it was observed here. `%` is the shortest header
        // that reaches it; `application/json; charset=@@` reaches it too, so it is a
        // valid type with a bad parameter and not only obvious garbage.
        listOf(null, "%", "application/json; charset=@@").forEach { contentType ->
            val response = postRaw("/auth/logout", session = null, contentType = contentType)
            assertThat(response.statusCode()).describedAs("Content-Type %s", contentType).isEqualTo(415)
            assertThat(response.json().get("code").asText()).isEqualTo("UNSUPPORTED_MEDIA_TYPE")
        }
    }

    /**
     * Deliberately not [GoogleLoginFixture.post], which now always sends
     * `application/json` because that is what a real client sends. This one owns the
     * header so a test can omit it.
     */
    private fun postRaw(
        path: String,
        session: HttpCookie?,
        contentType: String?,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .apply {
                    if (contentType != null) header("Content-Type", contentType)
                    if (session != null) header("Cookie", "${session.name}=${session.value}")
                }.build()
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    }
}
