package com.donghaeng.config

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment

internal class MissingProfileException :
    RuntimeException(
        "Required property `${RequiredProfileMarker.MARKER_PROPERTY}` is not set, " +
            "which means no Spring profile is active.",
    )

/**
 * Fails startup when no profile is active. The marker property is defined only in
 * `application-dev.yml` and `application-prod.yml`, never in the base — so its
 * absence is exactly "started with no profile, or with a misspelled one".
 *
 * An [EnvironmentPostProcessor] rather than a bean, and the distinction is the
 * whole point: as a `@Configuration` bean the check ran *after* the application
 * had already opened its first connection to whatever `DATABASE_URL` pointed
 * at, and done so under the settings of the profile that was not meant to be
 * active. Flyway used to be what opened it; since
 * notes/2026-08-09-decision-schema-ownership.md it no longer runs at startup
 * anywhere, and the first connection is now Hibernate's database-metadata
 * lookup while the EntityManagerFactory is built — which under `ddl-auto:
 * validate` also reads the schema. The conclusion is unchanged, and that is the
 * point: the ordering has to hold whatever happens to open the connection next.
 * Here the check runs while the environment is being prepared, before any bean
 * exists.
 */
internal class RequiredProfileMarker : EnvironmentPostProcessor {
    override fun postProcessEnvironment(
        environment: ConfigurableEnvironment,
        application: SpringApplication,
    ) {
        if (environment.getProperty(MARKER_PROPERTY).isNullOrBlank()) {
            throw MissingProfileException()
        }
    }

    companion object {
        const val MARKER_PROPERTY = "donghaeng.profile"
    }
}
