package com.donghaeng.config

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer
import org.springframework.boot.diagnostics.FailureAnalysis

/**
 * Turns the missing-profile failure into Boot's "APPLICATION FAILED TO START"
 * block. Without it the operator gets a BeanCreationException stack trace, which
 * is the thing this check was supposed to replace, not reproduce.
 *
 * Registered in META-INF/spring.factories — a FailureAnalyzer runs before the
 * context exists, so it cannot be a bean.
 */
internal class MissingProfileFailureAnalyzer : AbstractFailureAnalyzer<MissingProfileException>() {
    fun analyze(cause: MissingProfileException): FailureAnalysis? = analyze(cause, cause)

    override fun analyze(
        rootFailure: Throwable,
        cause: MissingProfileException,
    ): FailureAnalysis =
        FailureAnalysis(
            "No Spring profile is active: the required property " +
                "`${RequiredProfileMarker.MARKER_PROPERTY}` is not set. It is defined only in " +
                "application-dev.yml and application-prod.yml, never in application.yml.",
            "Start the application with SPRING_PROFILES_ACTIVE=dev or SPRING_PROFILES_ACTIVE=prod.\n" +
                "Locally: SPRING_PROFILES_ACTIVE=dev sealbox run -p donghaeng -- ./gradlew bootRun --no-daemon",
            cause,
        )
}
