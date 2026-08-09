package com.donghaeng.config

import com.donghaeng.DonghaengApplication
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.diagnostics.FailureAnalyzer
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.io.support.SpringFactoriesLoader
import org.springframework.mock.env.MockEnvironment

/**
 * A mistyped or forgotten profile must fail at startup rather than at the first
 * login attempt — by which time the app has already connected to the database
 * it was pointed at and served traffic under the wrong settings.
 */
class RequiredProfileMarkerTest {
    private val marker = RequiredProfileMarker()

    @Test
    fun `an environment with no profile marker fails startup`() {
        assertThatThrownBy { marker.postProcessEnvironment(MockEnvironment(), SpringApplication()) }
            .isInstanceOf(MissingProfileException::class.java)
    }

    @Test
    fun `a blank marker is not a profile either`() {
        val environment = MockEnvironment().withProperty(RequiredProfileMarker.MARKER_PROPERTY, "  ")

        assertThatThrownBy { marker.postProcessEnvironment(environment, SpringApplication()) }
            .isInstanceOf(MissingProfileException::class.java)
    }

    @Test
    fun `an environment carrying the marker starts`() {
        val environment = MockEnvironment().withProperty(RequiredProfileMarker.MARKER_PROPERTY, "dev")

        assertThatCode { marker.postProcessEnvironment(environment, SpringApplication()) }
            .doesNotThrowAnyException()
    }

    @Test
    fun `the failure names the fix rather than showing a stack trace`() {
        // Through the public FailureAnalyzer interface — the method Boot calls.
        val analyzer: FailureAnalyzer = MissingProfileFailureAnalyzer()
        val analysis = analyzer.analyze(MissingProfileException())

        assertThat(analysis).isNotNull
        assertThat(analysis!!.description).contains(RequiredProfileMarker.MARKER_PROPERTY)
        assertThat(analysis.action).contains("SPRING_PROFILES_ACTIVE=dev")
        assertThat(analysis.action).contains("prod")
    }

    /**
     * The checks above pass against a MockEnvironment even if this class is turned
     * back into a `@Configuration` bean — which is the shape that let the
     * datasource be built, and its first connection opened, before the check ever
     * ran. These two hold the fix in place: the registration, and the ordering it
     * buys.
     */
    @Test
    fun `the marker is registered as an EnvironmentPostProcessor, not as a bean`() {
        val registered =
            SpringFactoriesLoader
                .forDefaultResourceLocation(javaClass.classLoader)
                .load(
                    EnvironmentPostProcessor::class.java,
                    null,
                    // Boot's own post-processors need constructor arguments this
                    // loader cannot supply; ours is no-arg, so only ours must load.
                    SpringFactoriesLoader.FailureHandler { _, _, _ -> },
                )

        assertThat(registered).hasAtLeastOneElementOfType(RequiredProfileMarker::class.java)
    }

    @Test
    fun `the check runs before the datasource is built`() {
        // With no profile AND no DB environment, two things could fail: this check,
        // or the unresolvable ${DATABASE_URL} placeholder in the datasource. Which
        // one wins is the ordering assertion — as a bean, the datasource would.
        val application = SpringApplication(DonghaengApplication::class.java)
        application.webApplicationType = WebApplicationType.NONE

        val failure = catchThrowable { application.run() }

        val causeChain = generateSequence(failure) { it.cause.takeIf { cause -> cause !== it } }.toList()
        assertThat(causeChain).hasAtLeastOneElementOfType(MissingProfileException::class.java)
    }
}
