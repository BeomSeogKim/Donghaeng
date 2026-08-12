package com.donghaeng.auth.login

import org.apache.commons.logging.LogFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository

/**
 * The Google registration, built in code rather than bound from
 * `spring.security.oauth2.client.registration.*`.
 *
 * Its own file so that `#89` adds Kakao and Naver beside it instead of editing
 * [com.donghaeng.auth.SecurityConfig] — the filter chain is where the security posture lives, and a
 * stop that only adds a provider should not have to touch it.
 *
 * The reason it is code at all is the two credentials. A yml line reading
 * `${GOOGLE_CLIENT_ID}` makes the whole application refuse to start where that
 * variable is absent — CI, a fresh checkout, every machine that has never seen a
 * Google client — and the only way to soften that is an inline default, which is
 * exactly the shape `ProfileConfigurationTest` forbids for values that reference
 * the environment. Reading them here instead makes "unconfigured" an ordinary
 * state: the app boots, serves, and answers 404 at the login endpoint because
 * there is no registration to start a flow with.
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
                // attempt, where it surfaces as a bare 404 and an operator has no
                // way to tell "misconfigured" from "no such provider". The
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
        val REGISTRATION_ID = "google"
        val ISSUER_URI = "https://accounts.google.com"
    }
}
