package com.donghaeng

import com.donghaeng.auth.STUB_PROVIDER
import com.donghaeng.auth.SecurityConfig
import com.donghaeng.auth.session.SessionTokens
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.BeforeEach
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.HttpCookie
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** The authorization code [STUB_PROVIDER] answers to; nothing asserts its value. */
internal const val STUB_AUTHORIZATION_CODE = "stub-authorization-code"

/**
 * What every endpoint test needs and no endpoint test should re-invent: a server on
 * a port, a Postgres carrying the migration files, an HTTP client that does not
 * follow redirects, and a session.
 *
 * Split out of `GoogleLoginFixture` when the first domain test arrived: a wedding
 * test extending a class called "GoogleLogin" is a name that has stopped saying what
 * it is, and there are fourteen more domains to come. **The OAuth round trip taken
 * APART — the state parameter, a tampered callback, a smuggled cookie — stays in
 * `GoogleLoginFixture`.** What lives here is [login], which hands back a session and
 * says nothing about how it was earned.
 *
 * **The four helpers below moved here when the fourth copy of them appeared** —
 * every wedding-scoped test needs a wedding to be scoped to, a second person to be
 * refused as, the caller's own id to check an audit column against, and a problem
 * document with `instance` taken off so two refusals can be compared. None of them
 * is a claim about a domain; each is the same arrangement four test classes were
 * making by hand. A helper that says something about ONE domain — the FK-ordered
 * `clean()` the guest tests share, for instance — belongs in that domain's own
 * fixture instead.
 */
internal abstract class ApiFixture {
    @LocalServerPort
    protected var port: Int = 0

    protected val mapper = ObjectMapper()

    /**
     * No cookie handler on purpose. An automatic jar would quietly re-send a
     * previous test's session and turn "the browser arrived with no cookie" into a
     * claim nothing checks; here every request carries exactly the cookies the test
     * named.
     */
    private val client: HttpClient =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    /**
     * [STUB_PROVIDER] is process-global mutable state shared by every test class, so
     * a test that reads it without setting it is logging in as whoever the last class
     * to run decided. Reset here rather than in each class: the classes that care set
     * their own values in their own `@BeforeEach`, which JUnit runs after this one.
     */
    @BeforeEach
    fun resetStubProvider() {
        STUB_PROVIDER.subject = "stub-subject"
        STUB_PROVIDER.email = null
        STUB_PROVIDER.emailVerified = false
        STUB_PROVIDER.fullName = "테스터"
    }

    protected fun get(
        path: String,
        cookies: List<HttpCookie> = emptyList(),
    ): HttpResponse<String> = send(HttpRequest.newBuilder(uri(path)).GET(), cookies)

    /**
     * **`Content-Type: application/json` even when the body is empty**, because every
     * state-changing handler declares a `consumes` and a request without one does not
     * match the mapping at all. That is the point rather than an inconvenience — it
     * is what forces the CORS preflight — so the fixture sends what a real client
     * must. `ContentTypeGateContractTest` is where its absence is asserted.
     */
    protected fun post(
        path: String,
        cookies: List<HttpCookie> = emptyList(),
        body: String? = null,
    ): HttpResponse<String> =
        send(
            HttpRequest
                .newBuilder(uri(path))
                .POST(body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
                .header("Content-Type", "application/json"),
            cookies,
        )

    /**
     * A whole login, from no cookie to the session cookie the server issued. The
     * decomposed version, for tests that need to interfere with a step, is in
     * `GoogleLoginFixture`.
     */
    protected fun login(): HttpCookie {
        val authorization = get(SecurityConfig.AUTHORIZATION_PATH)
        check(authorization.statusCode() == 302) { "expected a redirect to the provider, got ${authorization.statusCode()}" }
        val parameters = queryParameters(authorization.location())
        STUB_PROVIDER.nonce = parameters["nonce"]

        val callback =
            get(
                "${SecurityConfig.CALLBACK_PATH}?code=$STUB_AUTHORIZATION_CODE&state=${parameters.getValue("state")}",
                authorization.cookies(),
            )
        check(callback.statusCode() == 302) { "login did not complete: ${callback.statusCode()} ${callback.body()}" }
        return callback.sessionCookie() ?: error("login issued no session cookie")
    }

    /**
     * A wedding, and the caller's membership in it — the state every wedding-scoped
     * endpoint needs before it can be called at all. Created over HTTP rather than
     * inserted, so a test is scoped to a wedding the API itself would accept.
     *
     * [groomName] is a parameter only because a test that creates two weddings wants
     * to tell them apart in a failure message.
     */
    protected fun createWedding(
        session: HttpCookie,
        groomName: String = "김신랑",
    ): Long =
        post(
            "/weddings",
            listOf(session),
            """{"weddingDate":"2026-10-10","groomName":"$groomName","brideName":"이신부"}""",
        ).json()["id"]
            .asLong()

    /**
     * A second person, with their own `app_user` row and their own session.
     *
     * The standing fact this exists for: a person may belong to several weddings and
     * a wedding to several people, so "the caller's wedding" is never a property of
     * the session — it is a walk that can fail, and a tenancy test needs someone for
     * it to fail for (notes/2026-08-19-decision-wedding-scope-gate.md §2b).
     */
    protected fun loginAs(subject: String): HttpCookie {
        STUB_PROVIDER.subject = subject
        return login()
    }

    /**
     * The caller's own id, for the audit columns no response publishes.
     *
     * Named for what it returns rather than for the endpoint it asks: `SessionResolutionTest`
     * has its own `me()` that hands back the whole response, and that one is about
     * `/auth/me` itself.
     */
    protected fun callerId(session: HttpCookie): Long = get("/auth/me", listOf(session)).json()["id"].asLong()

    /**
     * A problem document with `instance` removed, which is the only member two
     * refusals of DIFFERENT ids may legitimately differ in. Comparing what is left
     * is how "a stranger's wedding answers exactly what a nonexistent one answers"
     * is asserted rather than asserted-about
     * (notes/2026-08-10-decision-cross-tenant-status-code.md).
     */
    protected fun withoutInstance(response: HttpResponse<String>): Map<*, *> =
        mapper.readValue(response.body(), Map::class.java).filterKeys { it != "instance" }

    private fun uri(path: String): URI = URI.create("http://localhost:$port$path")

    private fun send(
        builder: HttpRequest.Builder,
        cookies: List<HttpCookie>,
    ): HttpResponse<String> {
        if (cookies.isNotEmpty()) {
            builder.header("Cookie", cookies.joinToString("; ") { "${it.name}=${it.value}" })
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
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
         * [SharedPostgres], with Flyway building the schema — the suite is the only
         * place it ever runs (notes/2026-08-09-decision-schema-ownership.md), so
         * every test here runs against `V1`+`V2` exactly as the founder types them,
         * and `ddl-auto: validate` compares the entity mappings to the result.
         */
        @JvmStatic
        @DynamicPropertySource
        fun productionShapedEnvironment(registry: DynamicPropertyRegistry) = SharedPostgres.publish(registry)
    }
}
