package com.donghaeng.auth

import com.donghaeng.ApiFixture
import com.donghaeng.STUB_AUTHORIZATION_CODE
import com.donghaeng.auth.oauth.GoogleClientRegistration
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
import java.net.HttpCookie
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
 *
 * Every test class starts from a known value regardless: [ApiFixture] resets the
 * four mutable fields before each test.
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
 * The OAuth round trip **taken apart**, for the tests that interfere with a step of
 * it — a tampered `redirect_uri`, a wrong code, a `state` from another browser, a
 * session cookie smuggled into the callback.
 *
 * A test that only needs to be logged in extends [ApiFixture] and calls `login()`;
 * everything transport-shaped lives there.
 */
internal abstract class GoogleLoginFixture : ApiFixture() {
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
        code: String = STUB_AUTHORIZATION_CODE,
        extraQuery: String = "",
    ): HttpResponse<String> {
        STUB_PROVIDER.nonce = authorization.parameters["nonce"]
        val state = authorization.parameters.getValue("state")
        return get("${SecurityConfig.CALLBACK_PATH}?code=$code&state=$state$extraQuery", authorization.cookies)
    }

    /** A whole login that also presents [presented] at the callback. */
    protected fun login(presented: List<HttpCookie>): HttpCookie {
        val authorization = startAuthorization()
        val response = completeAuthorization(authorization.copy(cookies = authorization.cookies + presented))
        check(response.statusCode() == 302) { "login did not complete: ${response.statusCode()} ${response.body()}" }
        return response.sessionCookie() ?: error("login issued no session cookie")
    }
}
