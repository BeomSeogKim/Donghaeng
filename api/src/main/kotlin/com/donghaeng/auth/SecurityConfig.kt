package com.donghaeng.auth

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.commons.logging.LogFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.savedrequest.NullRequestCache

/**
 * The Google registration, built in code rather than bound from
 * `spring.security.oauth2.client.registration.*`.
 *
 * The reason is the two credentials. A yml line reading `${GOOGLE_CLIENT_ID}`
 * makes the whole application refuse to start where that variable is absent — CI,
 * a fresh checkout, every machine that has never seen a Google client — and the
 * only way to soften that is an inline default, which is exactly the shape
 * `ProfileConfigurationTest` forbids for values that reference the environment.
 * Reading them here instead makes "unconfigured" an ordinary state: the app boots,
 * serves, and answers 404 at the login endpoint because there is no registration
 * to start a flow with.
 *
 * The URIs come from [CommonOAuth2Provider], which is Spring Security's own
 * maintained copy of them. [ClientRegistration.ProviderDetails.getIssuerUri] is
 * restated anyway because it is not decoration: `OidcIdTokenValidator` checks the
 * `iss` claim **only when it is set**, so a null there silently reduces "full
 * ID-token validation" (notes/2026-07-30-decision-network-security.md) to
 * signature and expiry.
 */
@Configuration
internal class GoogleClientRegistration {
    private val logger = LogFactory.getLog(javaClass)

    @Bean
    fun clientRegistrationRepository(
        @Value("\${GOOGLE_CLIENT_ID:}") clientId: String,
        @Value("\${GOOGLE_CLIENT_SECRET:}") clientSecret: String,
    ): ClientRegistrationRepository {
        val google =
            if (clientId.isBlank() || clientSecret.isBlank()) {
                // Said at startup rather than discovered at the first login
                // attempt, where it surfaces as a masked 500 and an operator has to
                // read a stack trace to learn that a variable is missing. The
                // credential itself is never logged — only whether it is there.
                logger.warn(
                    "GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET are not both set, so Google login cannot run " +
                        "in this environment. Every other endpoint is unaffected.",
                )
                null
            } else {
                CommonOAuth2Provider.GOOGLE
                    .getBuilder(REGISTRATION_ID)
                    .clientId(clientId)
                    .clientSecret(clientSecret)
                    .issuerUri(ISSUER_URI)
                    .build()
            }

        return ClientRegistrationRepository { requested -> google?.takeIf { it.registrationId == requested } }
    }

    companion object {
        const val REGISTRATION_ID = "google"
        const val ISSUER_URI = "https://accounts.google.com"
    }
}

/**
 * One filter chain, and every deviation from Spring Security's defaults below is
 * deliberate.
 *
 * **`permitAll` everywhere, in every environment.** The gate is the resolver, not
 * the filter chain (notes/2026-08-10-decision-auth-gate-and-sequence.md): a
 * request is rejected because `user → membership → wedding` resolution failed, and
 * that stays true in production. It is a design rather than deferred hardening
 * because the retrofit costs differ — flipping this line later is one line and
 * announces itself in CI, whereas retrofitting [CurrentUser] means threading a
 * parameter through every endpoint written in the meantime, by hand and silently.
 *
 * **CSRF is off, and the substitute is named.** v1's answer is `SameSite=Lax` plus
 * no state-changing GET — the pair, not either half, since Lax admits the cookie
 * on top-level GET navigation. A CSRF token is defense in depth and belongs to
 * `#48`.
 *
 * The one state-changing GET this application has is the **OAuth callback**, and
 * it is the exception that proves the rule rather than a violation of it: its
 * defense is OAuth's own, the `state` parameter plus PKCE, which is strictly
 * stronger than a cookie attribute. No other GET may create or change anything.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(SessionProperties::class)
internal class SecurityConfig {
    @Bean
    fun filterChain(
        http: HttpSecurity,
        registrations: ClientRegistrationRepository,
        logins: LoginService,
        cookies: SessionCookies,
        objectMapper: ObjectMapper,
        @Value("\${donghaeng.frontend.base-url:}") frontendBaseUrl: String,
    ): SecurityFilterChain {
        http {
            authorizeHttpRequests { authorize(anyRequest, permitAll) }
            // Never silent: the substitute is `SameSite=Lax` plus no
            // state-changing GET, stated in full above and held by
            // SessionCookies. A token is #48
            // (notes/2026-08-10-decision-auth-gate-and-sequence.md, CSRF).
            csrf { disable() }

            // Nothing is cached, so nothing cached can steer the post-login
            // redirect. Belt to OAuthLoginSuccessHandler's braces.
            requestCache { requestCache = NullRequestCache() }

            // A JSON API has no login form, no browser-prompt realm, and — until
            // its own logout endpoint exists — no `/logout`. Each of these is
            // registered by default and each would be a surface answering for
            // behaviour this application does not implement.
            formLogin { disable() }
            httpBasic { disable() }
            logout { disable() }

            oauth2Login {
                // Suppresses Spring Security's generated HTML login page: this API
                // serves JSON only, and there is exactly one provider to pick.
                loginPage = AUTHORIZATION_PATH
                authorizationEndpoint { authorizationRequestResolver = pkceResolver(registrations) }
                authenticationSuccessHandler = OAuthLoginSuccessHandler(logins, cookies, frontendBaseUrl)
                authenticationFailureHandler = OAuthLoginFailureHandler(objectMapper)
            }
        }
        return http.build()
    }

    /**
     * PKCE, turned on explicitly — **it is not on by default for a client that
     * holds a secret.** Spring Security enables it automatically only for public
     * clients, so a confidential registration like ours gets none unless this line
     * exists, and the security record lists PKCE among the things whose absence is
     * an account-takeover path.
     *
     * What it buys with a confidential client: an authorization code intercepted
     * on its way back — a leaky proxy, a browser extension, a rogue app holding the
     * redirect — cannot be exchanged, because the exchange also demands the
     * verifier that never left this server.
     */
    private fun pkceResolver(registrations: ClientRegistrationRepository): OAuth2AuthorizationRequestResolver =
        DefaultOAuth2AuthorizationRequestResolver(registrations, AUTHORIZATION_BASE_URI).apply {
            setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce())
        }

    internal companion object {
        const val AUTHORIZATION_BASE_URI = "/oauth2/authorization"

        /** Spring Security's default, restated because `web/` links to it. */
        const val AUTHORIZATION_PATH = "$AUTHORIZATION_BASE_URI/${GoogleClientRegistration.REGISTRATION_ID}"

        /**
         * Spring Security's default redirection endpoint, and the value the
         * founder types into the Google console — where it is matched exactly.
         * Kept at the default precisely so those two are the same string.
         */
        const val CALLBACK_PATH = "/login/oauth2/code/${GoogleClientRegistration.REGISTRATION_ID}"
    }
}
