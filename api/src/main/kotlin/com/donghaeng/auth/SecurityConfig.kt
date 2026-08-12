package com.donghaeng.auth

import com.donghaeng.auth.login.GoogleClientRegistration
import com.donghaeng.auth.login.LoginService
import com.donghaeng.auth.login.OAuthLoginFailureHandler
import com.donghaeng.auth.login.OAuthLoginSuccessHandler
import com.donghaeng.auth.session.CurrentUser
import com.donghaeng.auth.session.SessionCookies
import com.donghaeng.config.FrontendProperties
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
import org.springframework.security.web.savedrequest.NullRequestCache

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
internal class SecurityConfig {
    @Bean
    fun filterChain(
        http: HttpSecurity,
        registrations: ClientRegistrationRepository,
        logins: LoginService,
        cookies: SessionCookies,
        objectMapper: ObjectMapper,
        frontend: FrontendProperties,
    ): SecurityFilterChain {
        http {
            authorizeHttpRequests { authorize(anyRequest, permitAll) }

            // Never silent: the substitute is `SameSite=Lax` plus no
            // state-changing GET, stated in full above and held by
            // SessionCookies. A token is #48
            // (notes/2026-08-10-decision-auth-gate-and-sequence.md, CSRF).
            csrf { disable() }

            // The CORS policy is a bean so a profile can widen it and no profile
            // can widen it silently — see CorsPolicy.
            cors { }

            headers {
                // In the token baseline (notes/2026-07-30-decision-network-security.md)
                // and in Spring Security's default header set, which is not the
                // same thing — it writes X-Content-Type-Options, X-Frame-Options
                // and the cache headers, and no Referrer-Policy at all. Tokens
                // necessarily travel in URLs in this system: the OAuth callback
                // carries `code` and `state`, and the RSVP links will carry a
                // per-guest token when they return. Without this a browser
                // forwards that URL to whatever the landing page loads next.
                referrerPolicy { policy = ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER }
            }

            // Nothing is cached, so nothing cached can steer the post-login
            // redirect. Belt to OAuthLoginSuccessHandler's braces.
            requestCache { requestCache = NullRequestCache() }

            // A JSON API has no login form and no browser-prompt realm; both are
            // registered by default and both would answer for behaviour this
            // application does not implement.
            //
            // `/logout` stays disabled UNCONDITIONALLY, and now that
            // `POST /auth/logout` exists that is more load-bearing rather than
            // less. With `csrf { disable() }` above, Spring Security 6's
            // LogoutConfigurer stops narrowing its matcher to POST and registers
            // `/logout` for GET, PUT and DELETE as well — a state-changing GET,
            // which is precisely the half of v1's CSRF pair that has to be true
            // for the other half to mean anything. Our logout is a POST and lives
            // in AuthController.
            logout { disable() }
            formLogin { disable() }
            httpBasic { disable() }

            oauth2Login {
                // Suppresses Spring Security's generated HTML login page: this API
                // serves JSON only, and there is exactly one provider to pick.
                loginPage = AUTHORIZATION_PATH
                authorizationEndpoint { authorizationRequestResolver = pkceResolver(registrations) }
                authenticationSuccessHandler = OAuthLoginSuccessHandler(logins, cookies, frontend)
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
     *
     * Wrapped in [unknownProviderIsNotFound] for a reason unrelated to PKCE; see
     * there.
     */
    private fun pkceResolver(registrations: ClientRegistrationRepository): OAuth2AuthorizationRequestResolver =
        unknownProviderIsNotFound(
            DefaultOAuth2AuthorizationRequestResolver(registrations, AUTHORIZATION_BASE_URI).apply {
                setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce())
            },
            registrations,
        )

    /**
     * Makes `/oauth2/authorization/{anything-else}` a 404 instead of a 500.
     *
     * Unguarded, [DefaultOAuth2AuthorizationRequestResolver] throws for an id it
     * does not know, and `OAuth2AuthorizationRequestRedirectFilter` turns that into
     * `sendError(500)` plus a logged stack trace. That is an **anonymous 500
     * generator on an unauthenticated path** — unbounded ERROR-log volume against
     * the one detection capability the security record keeps (alerting on
     * 401/404/429 spikes), and unrateable, since the standing rate-limit unit is
     * per wedding and per link token and this request has neither.
     * `.github/workflows/ci.yml` already makes this exact argument about `/error`;
     * this is the same hole with a different door.
     *
     * Asked of the repository rather than caught, because the exception Spring
     * raises for it is package-private and so cannot be named here — and catching
     * its public supertype would also swallow real faults as 404s.
     *
     * Returning `null` means "not an authorization request", so the filter falls
     * through and the path 404s like any other unmapped one. It also gives the
     * unconfigured environment an honest answer — there is no such provider here —
     * rather than an incident report.
     */
    private fun unknownProviderIsNotFound(
        delegate: OAuth2AuthorizationRequestResolver,
        registrations: ClientRegistrationRepository,
    ): OAuth2AuthorizationRequestResolver =
        object : OAuth2AuthorizationRequestResolver {
            // Each overload guards and then calls THE SAME overload on the
            // delegate. Routing the one-argument call through the two-argument one
            // looks equivalent and is not: Spring expands `{action}` in the
            // registration's redirect-uri template to "login" for the first and
            // "authorize" for the second, so the shortcut silently turns the
            // callback into `/authorize/oauth2/code/google` — a URL that is not the
            // one registered in the Google console, which matches it exactly. The
            // login tests assert the redirect_uri for this reason.
            override fun resolve(request: HttpServletRequest): OAuth2AuthorizationRequest? =
                registrationIdOf(request)?.takeIf(::known)?.let { delegate.resolve(request) }

            override fun resolve(
                request: HttpServletRequest,
                clientRegistrationId: String,
            ): OAuth2AuthorizationRequest? =
                clientRegistrationId
                    .takeIf(::known)
                    ?.let { delegate.resolve(request, clientRegistrationId) }

            private fun known(registrationId: String) = registrations.findByRegistrationId(registrationId) != null

            /**
             * The `{registrationId}` of `/oauth2/authorization/{registrationId}`.
             *
             * This duplicates a path match Spring also does, and the duplication is
             * safe in both directions: a `null` here means the delegate is never
             * called and the filter falls through to a 404, while a non-null one is
             * only ever used to ask whether that registration exists — after which
             * Spring does its own matching on the real request.
             */
            private fun registrationIdOf(request: HttpServletRequest): String? {
                val prefix = "$AUTHORIZATION_BASE_URI/"
                val path = request.requestURI.removePrefix(request.contextPath)
                if (!path.startsWith(prefix)) return null
                return path.removePrefix(prefix).takeIf { it.isNotEmpty() && !it.contains('/') }
            }
        }

    internal companion object {
        val AUTHORIZATION_BASE_URI = "/oauth2/authorization"

        /** Spring Security's default, restated because `web/` links to it. */
        val AUTHORIZATION_PATH = "$AUTHORIZATION_BASE_URI/${GoogleClientRegistration.REGISTRATION_ID}"

        /**
         * Spring Security's default redirection endpoint, and the value the
         * founder types into the Google console — where it is matched exactly.
         * Kept at the default precisely so those two are the same string.
         */
        val CALLBACK_PATH = "/login/oauth2/code/${GoogleClientRegistration.REGISTRATION_ID}"
    }
}
