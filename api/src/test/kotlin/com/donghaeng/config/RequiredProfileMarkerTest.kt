package com.donghaeng.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.mock.env.MockEnvironment

/**
 * A mistyped or forgotten profile must fail at startup rather than at the first
 * login attempt — by which time Flyway has already migrated the database it was
 * pointed at.
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
        val analysis = MissingProfileFailureAnalyzer().analyze(MissingProfileException())

        assertThat(analysis).isNotNull
        assertThat(analysis!!.description).contains(RequiredProfileMarker.MARKER_PROPERTY)
        assertThat(analysis.action).contains("SPRING_PROFILES_ACTIVE=dev")
        assertThat(analysis.action).contains("prod")
    }
}
