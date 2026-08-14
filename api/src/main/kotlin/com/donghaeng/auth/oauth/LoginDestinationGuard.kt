package com.donghaeng.auth.oauth

import com.donghaeng.config.FrontendProperties
import org.springframework.beans.factory.InitializingBean
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.stereotype.Component

/**
 * Refuses to start an environment that can begin a login and cannot finish one.
 *
 * The two settings are independent in configuration and not independent in fact:
 * a Google `ClientRegistration` means a person can reach the consent screen, and
 * [FrontendProperties.baseUrl] is the only place they can be sent afterwards. With
 * the first set and the second blank, a **successful** login dies in
 * [OAuthLoginSuccessHandler]'s `check` as a masked 500 with a stack trace, at the
 * one moment the person has just typed their password.
 *
 * So the pairing is asserted at startup, where nobody has logged in yet, rather
 * than left to a comment. It costs a legitimately unconfigured environment
 * nothing: with no Google credentials there is no registration, the app boots and
 * serves every other endpoint, and `/oauth2/authorization/google` is a 404 — which
 * is production's state until `#96`.
 *
 * It reads the resolved [ClientRegistrationRepository] rather than the two
 * `GOOGLE_*` variables, so it stays true for whatever `#89` adds beside Google.
 */
@Component
internal class LoginDestinationGuard(
    private val registrations: ClientRegistrationRepository,
    private val frontend: FrontendProperties,
) : InitializingBean {
    override fun afterPropertiesSet() {
        val loginIsPossible = registrations.findByRegistrationId(GoogleClientRegistration.REGISTRATION_ID) != null
        check(!loginIsPossible || frontend.baseUrl.isNotBlank()) {
            "Google login is configured but `donghaeng.frontend.base-url` is not, so a completed login " +
                "would have nowhere to land. Set DONGHAENG_FRONTEND_BASE_URL, or remove GOOGLE_CLIENT_ID " +
                "and GOOGLE_CLIENT_SECRET."
        }
    }
}
