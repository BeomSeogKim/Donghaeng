package com.donghaeng.auth.oauth

import com.donghaeng.config.FrontendProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository

/**
 * The two settings that are independent in configuration and not in fact: a
 * configured provider means a person can reach a consent screen, and
 * `donghaeng.frontend.base-url` is the only place they can be sent afterwards.
 * Half-configured, a **successful** login is a masked 500 at the moment someone
 * has just typed their password.
 *
 * Named for the `prod-boot` job's pattern deliberately — the state this refuses is
 * a deploy-platform state, assembled from two variables typed on different days,
 * and the job that rehearses configuration is where it belongs. That the guard is
 * also present in the real booted contexts is asserted by
 * `RealConfigurationBootTest`; a rule nothing runs is not a rule.
 */
internal class LoginDestinationProfileBootTest {
    private val runner = ApplicationContextRunner().withBean(LoginDestinationGuard::class.java)

    @Test
    fun `a configured provider with nowhere to land refuses to start`() {
        runner
            .withBean(ClientRegistrationRepository::class.java, ::google)
            .withBean(FrontendProperties::class.java, { FrontendProperties(baseUrl = "") })
            .run { context ->
                assertThat(context).hasFailed()
                // The message has to name the fix: whoever reads it is looking at a
                // deploy that boots everywhere else.
                assertThat(context.startupFailure!!)
                    .hasStackTraceContaining("donghaeng.frontend.base-url")
                    .hasStackTraceContaining("DONGHAENG_FRONTEND_BASE_URL")
            }
    }

    @Test
    fun `a configured provider with a destination starts`() {
        runner
            .withBean(ClientRegistrationRepository::class.java, ::google)
            .withBean(FrontendProperties::class.java, { FrontendProperties(baseUrl = "https://donghaeng.example") })
            .run { context -> assertThat(context).hasNotFailed() }
    }

    @Test
    fun `no provider and no destination is an ordinary environment`() {
        // Production's state until #96: nobody can start a login, so nobody can
        // fail to finish one, and every other endpoint works.
        runner
            .withBean(ClientRegistrationRepository::class.java, { ClientRegistrationRepository { null } })
            .withBean(FrontendProperties::class.java, { FrontendProperties(baseUrl = "") })
            .run { context -> assertThat(context).hasNotFailed() }
    }

    private fun google(): ClientRegistrationRepository {
        val registration: ClientRegistration =
            CommonOAuth2Provider.GOOGLE
                .getBuilder(GoogleClientRegistration.REGISTRATION_ID)
                .clientId("stub")
                .clientSecret("stub")
                .build()
        return ClientRegistrationRepository { requested -> registration.takeIf { it.registrationId == requested } }
    }
}
