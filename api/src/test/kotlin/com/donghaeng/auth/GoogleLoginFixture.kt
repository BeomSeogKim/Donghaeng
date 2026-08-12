package com.donghaeng.auth

import com.donghaeng.SharedPostgres
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.HttpCookie
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** Fixed, fake, and never a real credential — see [StubOidcProvider]. */
internal const val STUB_CLIENT_ID = "stub-client-id"
internal const val STUB_CLIENT_SECRET = "stub-client-secret"

/**
 * One provider for the whole suite, started before any Spring context is built so
 * [StubGoogleRegistration] can read its port.
 *
 * It is mutable process-global state, and that is safe for exactly one reason:
 * nothing in this suite runs in parallel. JUnit's parallel execution is off by
 * default and no `junit-platform.properties` turns it on — the day one does, the
 * per-test `subject`/`email` assignments in the login tests start racing each
 * other and the failures will look like flaky OAuth rather than like this.
 */
internal val STUB_PROVIDER =
    StubOidcProvider(STUB_CLIENT_ID, STUB_CLIENT_SECRET).apply {
        start()
        Runtime.getRuntime().addShutdownHook(Thread(::stop))
    }

/**
 * Replaces the production registration with one pointing at [STUB_PROVIDER].
 *
 * Only the four endpoint URIs and the two credentials differ from what
 * [GoogleClientRegistration] builds. Everything the security record cares about is
 * left exactly as production has it — the scopes including `openid`, the
 * client-authentication method, the default redirect-uri template, and a non-null
 * `issuerUri`, without which `OidcIdTokenValidator` skips the `iss` check
 * altogether.
 */
@TestConfiguration
internal class StubGoogleRegistration {
    /**
     * `@Primary` under its own bean name rather than an override of the production
     * one: overriding depends on definition order and fails silently by simply not
     * happening, which is how the first version of this file spent a run asserting
     * against the real registration.
     */
    @Bean
    @Primary
    fun stubClientRegistrationRepository(): ClientRegistrationRepository =
        InMemoryClientRegistrationRepository(
            CommonOAuth2Provider.GOOGLE
                .getBuilder(GoogleClientRegistration.REGISTRATION_ID)
                .clientId(STUB_CLIENT_ID)
                .clientSecret(STUB_CLIENT_SECRET)
                .issuerUri(STUB_PROVIDER.issuer)
                .authorizationUri(STUB_PROVIDER.authorizationUri)
                .tokenUri(STUB_PROVIDER.tokenUri)
                .userInfoUri(STUB_PROVIDER.userInfoUri)
                .jwkSetUri(STUB_PROVIDER.jwkSetUri)
                .build(),
        )
}

/** One completed `/oauth2/authorization/google` redirect, taken apart. */
internal data class AuthorizationRequest(
    val parameters: Map<String, String>,
    val cookies: List<HttpCookie>,
)

/**
 * Everything the login tests share: a Postgres carrying the migration files, an
 * HTTP client that does NOT follow redirects (every assertion here is about a
 * `Location` header or a `Set-Cookie` on a 302), and a cookie jar the test drives
 * by hand so it can say exactly which cookies a request carried.
 */
internal abstract class GoogleLoginFixture {
    @LocalServerPort
    protected var port: Int = 0

    protected val mapper = ObjectMapper()

    /**
     * No cookie handler on purpose. An automatic jar would quietly re-send a
     * previous test's session and turn "the browser arrived with no cookie" into a
     * claim nothing checks; here every request carries exactly the cookies the
     * test named.
     */
    private val client: HttpClient =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    protected fun get(
        path: String,
        cookies: List<HttpCookie> = emptyList(),
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .GET()
                .apply {
                    if (cookies.isNotEmpty()) {
                        header("Cookie", cookies.joinToString("; ") { "${it.name}=${it.value}" })
                    }
                }.build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    /**
     * Logout is a POST, not a GET, and that is v1's CSRF answer rather than a REST
     * preference — `SameSite=Lax` admits the cookie on top-level GET navigation,
     * so a GET logout is an `<img src>` away from signing the couple out.
     */
    protected fun post(
        path: String,
        cookies: List<HttpCookie> = emptyList(),
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .apply {
                    if (cookies.isNotEmpty()) {
                        header("Cookie", cookies.joinToString("; ") { "${it.name}=${it.value}" })
                    }
                }.build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    /** Step one of the round trip: the browser is sent to the provider. */
    protected fun startAuthorization(): AuthorizationRequest {
        val response = get(SecurityConfig.AUTHORIZATION_PATH)
        check(response.statusCode() == 302) { "expected a redirect to the provider, got ${response.statusCode()}" }
        return AuthorizationRequest(
            parameters = queryParameters(response.location()),
            cookies = response.cookies(),
        )
    }

    /**
     * Step two: the browser comes back from the provider. [extraQuery] is how a
     * test smuggles something into the callback — the open-redirect attempt uses
     * it.
     */
    protected fun completeAuthorization(
        authorization: AuthorizationRequest,
        code: String = "stub-authorization-code",
        extraQuery: String = "",
    ): HttpResponse<String> {
        STUB_PROVIDER.nonce = authorization.parameters["nonce"]
        val state = authorization.parameters.getValue("state")
        return get("${SecurityConfig.CALLBACK_PATH}?code=$code&state=$state$extraQuery", authorization.cookies)
    }

    /** A whole login, from no cookie to the session cookie the server issued. */
    protected fun login(presented: List<HttpCookie> = emptyList()): HttpCookie {
        val authorization = startAuthorization()
        val response = completeAuthorization(authorization.copy(cookies = authorization.cookies + presented))
        check(response.statusCode() == 302) { "login did not complete: ${response.statusCode()} ${response.body()}" }
        return response.sessionCookie() ?: error("login issued no session cookie")
    }

    protected fun HttpResponse<*>.location(): URI = URI.create(headers().firstValue("Location").orElseThrow())

    protected fun HttpResponse<*>.cookies(): List<HttpCookie> = headers().allValues("Set-Cookie").flatMap(HttpCookie::parse)

    protected fun HttpResponse<*>.sessionCookie(): HttpCookie? = cookies().firstOrNull { it.name == SessionTokens.COOKIE_NAME }

    protected fun HttpResponse<*>.setCookieHeader(name: String): String? =
        headers().allValues("Set-Cookie").firstOrNull { it.startsWith("$name=") }

    protected fun HttpResponse<String>.json(): JsonNode = mapper.readTree(body())

    protected fun queryParameters(uri: URI): Map<String, String> =
        (uri.rawQuery ?: "")
            .split("&")
            .filter { it.contains("=") }
            .associate { pair ->
                val (name, value) = pair.split("=", limit = 2)
                URLDecoder.decode(name, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
            }

    companion object {
        /**
         * [SharedPostgres], not a container of this fixture's own — which is what
         * this used to start, three lines above a verbatim copy of
         * [SharedPostgres.publish]. Two containers for one job, in a class named
         * for sharing.
         *
         * Flyway runs against it, because the suite is the only place it ever does
         * (notes/2026-08-09-decision-schema-ownership.md) — so these tests run
         * against `V1` and `V2` exactly as the founder will type them, and the dev
         * profile's `ddl-auto: validate` compares the entity mappings to the
         * result.
         */
        @JvmStatic
        @DynamicPropertySource
        fun productionShapedEnvironment(registry: DynamicPropertyRegistry) = SharedPostgres.publish(registry)
    }
}
