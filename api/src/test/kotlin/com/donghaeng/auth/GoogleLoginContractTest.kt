package com.donghaeng.auth

import com.donghaeng.auth.SecurityConfig.Companion.AUTHORIZATION_BASE_URI
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * THE RED GATE OF `#37`: a browser holding no cookie completes a Google
 * authorization-code round trip and comes back holding a session that resolves to
 * an `app_user`.
 *
 * Every request here goes over real HTTP to a real server, against a real Postgres
 * carrying `V1` and `V2`, through Spring Security's unmodified OAuth2 login filter
 * and against a provider that signs a real ID token ([StubOidcProvider]). Nothing
 * in the path under test is stubbed — only Google is.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@ActiveProfiles("dev")
@Import(StubGoogleRegistration::class, BareCurrentUserProbeController::class)
internal class GoogleLoginContractTest : GoogleLoginFixture() {
    @Autowired private lateinit var users: AppUserRepository

    @Autowired private lateinit var identities: OauthIdentityRepository

    @Autowired private lateinit var sessions: UserSessionRepository

    @BeforeEach
    fun clean() {
        sessions.deleteAll()
        identities.deleteAll()
        users.deleteAll()

        STUB_PROVIDER.subject = "google-subject-1"
        STUB_PROVIDER.email = null
        STUB_PROVIDER.emailVerified = false
        STUB_PROVIDER.fullName = "테스터"
    }

    @Test
    fun `a browser with no cookie logs in and comes back with a session that resolves to an app_user`() {
        STUB_PROVIDER.subject = "google-subject-42"
        STUB_PROVIDER.email = "kim@gmail.com"
        STUB_PROVIDER.emailVerified = true
        STUB_PROVIDER.fullName = "김테스터"

        val authorization = startAuthorization()
        val callback = completeAuthorization(authorization)

        // The browser is sent to the CONFIGURED frontend, and the session travels
        // in the cookie.
        assertThat(callback.statusCode()).isEqualTo(302)
        assertThat(callback.location()).hasToString("http://localhost:3000")
        val session = callback.sessionCookie() ?: error("no session cookie was issued")

        // "Resolves to an app_user" — asserted through the resolver, over HTTP,
        // carrying nothing but the cookie the server just set.
        val me = get("/auth/me", listOf(session))
        assertThat(me.statusCode()).isEqualTo(200)
        assertThat(me.json()["email"].asText()).isEqualTo("kim@gmail.com")
        assertThat(me.json()["name"].asText()).isEqualTo("김테스터")

        // And the two rows the login was supposed to write.
        assertThat(users.findAll()).singleElement().satisfies({ user ->
            assertThat(user.email).isEqualTo("kim@gmail.com")
            assertThat(user.emailVerifiedBy).isEqualTo("GOOGLE")
        })
        assertThat(identities.findAll()).singleElement().satisfies({ identity ->
            assertThat(identity.provider).isEqualTo("GOOGLE")
            assertThat(identity.providerUserId).isEqualTo("google-subject-42")
        })
    }

    @Test
    fun `an anonymous request to a session-scoped endpoint is 401 problem+json, not an HTML login page`() {
        val me = get("/auth/me")

        assertThat(me.statusCode()).isEqualTo(401)
        assertThat(me.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json")
        assertThat(me.json()["code"].asText()).isEqualTo("UNAUTHENTICATED")

        // Spring Security generates an HTML login page whenever a login mechanism
        // is enabled and no custom login page is named. This API serves JSON only,
        // so the generated page must not exist — asserted rather than assumed,
        // because it appears by default rather than by decision.
        assertThat(get("/login").statusCode()).isEqualTo(404)
    }

    @Test
    fun `every response tells the browser to forward no referrer`() {
        // In the token baseline (notes/2026-07-30-decision-network-security.md)
        // and NOT in Spring Security's default header set, which writes
        // X-Content-Type-Options, X-Frame-Options and the cache headers and no
        // Referrer-Policy at all. Tokens travel in URLs here — the callback
        // carries `code` and `state`, and the RSVP links will carry a per-guest
        // token — so without this the landing page hands that URL to whatever it
        // loads next.
        listOf(get("/auth/me"), get(SecurityConfig.AUTHORIZATION_PATH)).forEach { response ->
            assertThat(response.headers().firstValue("Referrer-Policy")).hasValue("no-referrer")
        }
    }

    @Test
    fun `the authorization request carries PKCE, and the token exchange proves the verifier was sent`() {
        val authorization = startAuthorization()

        // Spring Security does NOT enable PKCE for a client that holds a secret,
        // so this is the assertion that the explicit customizer is still wired.
        val challenge = authorization.parameters["code_challenge"]
        assertThat(authorization.parameters["code_challenge_method"]).isEqualTo("S256")
        assertThat(challenge).isNotNull()

        // And that it is honoured rather than merely advertised: the provider
        // recomputes the challenge from the verifier the exchange actually carried.
        completeAuthorization(authorization)
        assertThat(STUB_PROVIDER.lastCodeVerifier).isNotNull()
        assertThat(STUB_PROVIDER.verifierMatches(challenge)).isTrue()

        // The `state` parameter, the other half of the callback's CSRF defense —
        // and the reason the OAuth callback is allowed to be the one
        // state-changing GET in this application.
        assertThat(authorization.parameters["state"]).isNotBlank()

        // And the redirect_uri the exchange declared, which Google matches
        // EXACTLY against a value typed into its console by hand. Asserted here so
        // that moving off Spring Security's default callback path fails in the
        // suite rather than in a browser nobody can debug from the server side.
        assertThat(STUB_PROVIDER.lastRedirectUri).isEqualTo("http://localhost:$port${SecurityConfig.CALLBACK_PATH}")
    }

    @Test
    fun `the callback redirects only to the configured frontend, whatever the request asks for`() {
        val authorization = startAuthorization()

        val callback = completeAuthorization(authorization, extraQuery = "&redirect_uri=https://evil.example/steal")

        // The browser arrives here holding a session issued one line earlier, so an
        // open redirect on this path hands that session away. Nothing in the
        // request may steer it.
        assertThat(callback.location()).hasToString("http://localhost:3000")
    }

    @Test
    fun `logging in again re-issues the session, and the old one stops working`() {
        val first = login()
        assertThat(get("/auth/me", listOf(first)).statusCode()).isEqualTo(200)

        val second = login(presented = listOf(first))

        assertThat(second.value).isNotEqualTo(first.value)
        // Session fixation: an identifier that was valid before the login must not
        // be valid after it.
        assertThat(get("/auth/me", listOf(first)).statusCode()).isEqualTo(401)
        assertThat(get("/auth/me", listOf(second)).statusCode()).isEqualTo(200)
    }

    @Test
    fun `the session cookie is HttpOnly, SameSite=Lax and path-scoped`() {
        val callback = completeAuthorization(startAuthorization())
        val setCookie = callback.setCookieHeader(SessionTokens.COOKIE_NAME) ?: error("no session cookie")

        assertThat(setCookie).contains("HttpOnly")
        assertThat(setCookie).contains("Path=/")
        // Lax and NOT Strict: the callback above is a top-level cross-site
        // navigation, so Strict would withhold the cookie at the moment of login.
        assertThat(setCookie).contains("SameSite=Lax")
        // dev serves http://localhost, which a Secure cookie never reaches. The
        // base file pins `secure: true`; this profile is the one that loosens it.
        assertThat(setCookie).doesNotContain("Secure")
        assertThat(setCookie).doesNotContain("Domain=")
    }

    @Test
    fun `a second Google account with the same verified email merges onto the existing app_user`() {
        // #82: the index makes a duplicate impossible; it does not make the merge
        // work. Google hands back the address as the person typed it once and
        // lowercased the next time, and `ux_app_user_email` folds those together —
        // so a lookup written as `where email = ?` misses, takes the create branch,
        // and turns a silent account split into a 500 on login.
        STUB_PROVIDER.subject = "google-subject-first"
        STUB_PROVIDER.email = "Kim@Gmail.com"
        STUB_PROVIDER.emailVerified = true
        login()

        STUB_PROVIDER.subject = "google-subject-second"
        STUB_PROVIDER.email = "kim@gmail.com"
        val session = login()

        assertThat(users.findAll()).hasSize(1)
        assertThat(identities.findAll()).hasSize(2)
        assertThat(get("/auth/me", listOf(session)).json()["id"].asLong())
            .isEqualTo(users.findAll().single().id)
    }

    @Test
    fun `the merge lookup finds a row whose stored address is not lowercased`() {
        // The test above passes even with the lookup written as `where email = ?`,
        // because the address is lowercased on the way IN and so both sides are
        // already folded — which means it does not hold #82's obligation at all.
        // This one does: the row it has to find is stored with capitals, exactly as
        // `ux_app_user_email` permits.
        //
        // Such a row is not hypothetical. The unique index folds case, so the
        // column does not have to be lowercase, and anything that writes it without
        // going through GoogleProfile.mergeKey — a hand-applied fix, an import, the
        // Kakao path arriving at #89 — produces one. With `where email = ?` the
        // lookup then misses, the create branch runs, and the unique index turns a
        // silent account split into a 500 on login.
        val planted =
            users.save(
                AppUser(email = "Kim@Gmail.com", emailVerifiedBy = "GOOGLE", name = "김테스터"),
            )

        STUB_PROVIDER.subject = "google-subject-returning"
        STUB_PROVIDER.email = "kim@gmail.com"
        STUB_PROVIDER.emailVerified = true
        val session = login()

        assertThat(users.findAll()).hasSize(1)
        assertThat(get("/auth/me", listOf(session)).json()["id"].asLong()).isEqualTo(planted.id)
    }

    @Test
    fun `an unverified email is not stored at all, and the account stands alone`() {
        STUB_PROVIDER.subject = "google-subject-unverified"
        STUB_PROVIDER.email = "victim@gmail.com"
        STUB_PROVIDER.emailVerified = false

        val session = login()

        // Merging on an address nobody vouched for is a full ledger takeover with
        // no token and no expiry, so the address is not written and the account
        // stands alone (2026-08-11 §A). `ck_app_user_email_verified_by` makes the
        // pairing total, so a missing verifier means a missing address too.
        val user = users.findAll().single()
        assertThat(user.email).isNull()
        assertThat(user.emailVerifiedBy).isNull()
        assertThat(get("/auth/me", listOf(session)).json()["email"].isNull).isTrue()
    }

    @Test
    fun `a callback the provider refused comes back as problem+json, not as an HTML redirect`() {
        // A failure raised inside the OAuth2 login filter never reaches
        // @RestControllerAdvice — a filter is outside Spring MVC's exception
        // resolvers entirely (#62). Spring's default answer is a redirect to a
        // generated login page.
        val authorization = startAuthorization()
        val denied =
            get(
                "${SecurityConfig.CALLBACK_PATH}?error=access_denied&state=${authorization.parameters["state"]}",
                authorization.cookies,
            )

        assertThat(denied.statusCode()).isEqualTo(401)
        assertThat(denied.headers().firstValue("Content-Type").orElseThrow())
            .startsWith("application/problem+json")
        assertThat(denied.json()["code"].asText()).isEqualTo("OAUTH_LOGIN_DENIED")
    }

    @Test
    fun `a callback whose state was not issued here is refused`() {
        // The `state` check is what stops a forged callback from logging a victim
        // into the attacker's account. Spring Security owns it; this asserts it is
        // still switched on, since our own success handler sits directly behind it.
        val forged = get("${SecurityConfig.CALLBACK_PATH}?code=stub-authorization-code&state=not-ours")

        assertThat(forged.statusCode()).isEqualTo(401)
        assertThat(forged.json()["code"].asText()).isEqualTo("OAUTH_LOGIN_FAILED")
        assertThat(sessions.findAll()).isEmpty()
    }

    @Test
    fun `an unknown provider is a 404, not a logged 500 anyone can generate`() {
        // Unwrapped, Spring Security answers an unrecognised registration id with
        // sendError(500) and a logged stack trace — an anonymous, unauthenticated,
        // unrateable ERROR-log generator, against the one detection capability the
        // security record keeps (401/404/429 spikes). It is also the shape an
        // environment with no Google credentials is in, so this is not a
        // hypothetical path.
        val unknown = get("$AUTHORIZATION_BASE_URI/kakao")

        assertThat(unknown.statusCode()).isEqualTo(404)
        assertThat(unknown.headers().firstValue("Content-Type").orElseThrow())
            .startsWith("application/problem+json")
    }

    @Test
    fun `an AuthenticatedUser parameter is resolved even without the annotation`() {
        // The resolver keys on the TYPE, and this is the assertion that keeps it
        // that way. Matching on the annotation as well used to fail OPEN: Spring's
        // catch-all ModelAttribute processor took the parameter instead and built
        // an AuthenticatedUser from request parameters, so `?id=42` was an
        // identity. The probe controller below declares the parameter bare.
        val impersonation = get("/test-current-user/bare?id=42")

        assertThat(impersonation.statusCode()).isEqualTo(401)
        assertThat(impersonation.json()["code"].asText()).isEqualTo("UNAUTHENTICATED")

        // And it still resolves a real session, so the 401 above is the resolver
        // refusing rather than the parameter being unreachable.
        val session = login()
        val resolved = get("/test-current-user/bare", listOf(session))
        assertThat(resolved.statusCode()).isEqualTo(200)
        assertThat(resolved.body()).isEqualTo(
            users
                .findAll()
                .single()
                .id
                .toString(),
        )
    }

    @Test
    fun `the browser may call the API from the dev frontend origin, and from nowhere else`() {
        // #97. Credentials are involved because the session rides a cookie, so the
        // pairing matters: a response without Allow-Credentials is one the browser
        // discards even when the origin matches.
        val allowed = preflight("http://localhost:3000")
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Origin")).hasValue("http://localhost:3000")
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Credentials")).hasValue("true")

        // Never `*`, which is illegal alongside credentials anyway, and never a
        // suffix match — `donghaeng.kr.evil.com` is an origin an attacker
        // registers, and it is the case the pattern API exists to get wrong.
        listOf("https://evil.example", "http://localhost:3001", "https://donghaeng.kr.evil.com")
            .forEach { origin ->
                assertThat(preflight(origin).headers().firstValue("Access-Control-Allow-Origin"))
                    .describedAs("Allow-Origin for %s", origin)
                    .isEmpty()
            }
    }

    private fun preflight(origin: String) =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
            .send(
                HttpRequest
                    .newBuilder(URI.create("http://localhost:$port/auth/me"))
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                    .header("Origin", origin)
                    .header("Access-Control-Request-Method", "GET")
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
}

/**
 * Exists only to declare `AuthenticatedUser` WITHOUT `@CurrentUser`, which is the
 * shape a future handler will write by accident and which must still route through
 * the resolver.
 */
@RestController
internal class BareCurrentUserProbeController {
    @GetMapping("/test-current-user/bare")
    fun bare(caller: AuthenticatedUser): String = caller.id.toString()
}
