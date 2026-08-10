package com.donghaeng.config

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.util.ClassUtils

internal class SchemaOwnershipViolationException(
    val violation: String,
) : RuntimeException(violation)

/**
 * Refuses to start an environment in which the application could write DDL —
 * either by running a migration, or by letting Hibernate reshape the schema.
 * Both are the founder's to apply by hand
 * (notes/2026-08-09-decision-schema-ownership.md).
 *
 * It reads the RESOLVED value, and that is the entire reason it exists. Every
 * other guard on this decision inspects the committed yml files: a sweep over
 * `application*.yml` in ProfileConfigurationTest, and the settings restated in
 * `application-prod.yml` for a deploy reviewer. None of them can see
 * `SPRING_FLYWAY_ENABLED=true` typed into a deploy platform after a red boot —
 * which outranks every file in the jar, and would drop and recreate the ledger
 * schema at the next startup. An [EnvironmentPostProcessor] runs after config
 * data is loaded and sees environment variables, command-line arguments and yml
 * as one resolved value, which is the only vantage point where they are the
 * same fact.
 *
 * Both settings are checked as a WHITELIST of safe values rather than a
 * blacklist of unsafe ones, because the unsafe set is not enumerable: Spring
 * reads `on`, `yes` and `1` as true, and Hibernate takes several spellings of
 * "write the schema". An unrecognised value is refused.
 *
 * ## Why absence is a violation, for both keys
 *
 * Neither setting is safe when unset. Boot's `FlywayProperties` defaults
 * `enabled` to **true**, so a base file that lost its line reads as silence and
 * behaves as a migration at startup. `ddl-auto` unset resolves through Boot's
 * database detection — `create-drop` for an embedded database, `none`
 * otherwise — so its meaning depends on what the app connected to rather than
 * on anything anyone wrote down. Both are stated in `application.yml`, so
 * requiring them costs a running environment nothing.
 *
 * ## Why it is inert under the tests
 *
 * The suite legitimately runs Flyway — it is what builds the schema the tests
 * run against, from the same migration files the founder applies by hand — so
 * the guard has to be off there and on everywhere else. The signal chosen is
 * the presence of the **test classpath**: `spring-boot-starter-test` is a
 * `testImplementation` dependency, so `SpringBootTest` cannot be inside the
 * `bootJar`, which packages the runtime classpath only.
 *
 * The alternatives were both worse, and in the same way. Skipping when
 * `spring.flyway.enabled` arrives as a `-D` system property, or gating on a
 * private marker property, each leaves a string that turns the guard off from
 * the outside — which is the exact hole being closed, moved to a less obvious
 * name. A classpath probe has no environment-variable form at all.
 *
 * Its cost is real and worth stating: the guard silently disables itself if
 * `spring-boot-starter-test` ever reaches the runtime classpath, and the probe
 * is indirect enough that nobody would connect the two while making that
 * change. What limits the damage is that the failure is not silent for long —
 * CI's `docker` job boots the packaged image and would still be validating
 * against a real schema, and `underTest` is a constructor parameter precisely
 * so the guard's own behaviour is testable without depending on the probe.
 */
internal class SchemaOwnershipGuard(
    private val underTest: Boolean = TEST_CLASSPATH,
) : EnvironmentPostProcessor {
    // The two refusal messages below are a CI CONTRACT, not just diagnostics.
    // The `docker` job boots the packaged image with SPRING_FLYWAY_ENABLED=true
    // and with the raw hbm2ddl property set, and greps for exactly these
    // sentences — because an exit code alone would also be produced by an
    // unreachable database or a port clash, and would certify nothing. That job
    // is the only detector of the regression described under "why it is inert
    // under the tests" (issue #60). Reword either sentence and update
    // .github/workflows/ci.yml in the same change.
    //
    // These are the only strings in that check that are THIS class's; the other
    // two patterns it matches both prove that SchemaOwnershipFailureAnalyzer is
    // wired, not that the guard spoke. SchemaOwnershipGuardTest deliberately
    // does not assert these sentences — it builds its own — so that a reword
    // fails in CI rather than being edited into agreement alongside the change
    // that caused it.
    override fun postProcessEnvironment(
        environment: ConfigurableEnvironment,
        application: SpringApplication,
    ) {
        if (underTest) return

        val flyway = environment.getProperty(FLYWAY_ENABLED)
        if (flyway?.trim()?.lowercase() != "false") {
            throw SchemaOwnershipViolationException(
                "`$FLYWAY_ENABLED` resolved to ${describe(flyway)}, and this application never runs a " +
                    "migration outside its own tests.",
            )
        }

        val ddlAuto = environment.getProperty(DDL_AUTO)
        if (ddlAuto?.trim()?.lowercase() !in SAFE_DDL_AUTO) {
            refuseDdl(DDL_AUTO, ddlAuto)
        }

        // Unset is the ordinary case for the raw key — it is an override, not a
        // setting — so only a stated value is judged.
        val rawDdlAuto = environment.getProperty(HBM2DDL_AUTO)
        if (!rawDdlAuto.isNullOrBlank() && rawDdlAuto.trim().lowercase() !in SAFE_DDL_AUTO) {
            refuseDdl(HBM2DDL_AUTO, rawDdlAuto)
        }
    }

    private fun refuseDdl(
        key: String,
        value: String?,
    ): Nothing =
        throw SchemaOwnershipViolationException(
            "`$key` resolved to ${describe(value)}, and this application never writes DDL.",
        )

    private fun describe(value: String?) = if (value == null) "nothing (unset)" else "`$value`"

    companion object {
        const val FLYWAY_ENABLED = "spring.flyway.enabled"

        const val DDL_AUTO = "spring.jpa.hibernate.ddl-auto"

        /**
         * The raw Hibernate setting, and it is not a synonym that loses:
         * `HibernateProperties.determineDdlAuto` reads `hibernate.hbm2ddl.auto`
         * out of `spring.jpa.properties.*` BEFORE falling back to
         * [DDL_AUTO]. Checking Boot's alias alone would leave a second
         * environment variable that wins outright.
         */
        const val HBM2DDL_AUTO = "spring.jpa.properties.hibernate.hbm2ddl.auto"

        private val SAFE_DDL_AUTO = setOf("validate", "none")

        private val TEST_CLASSPATH =
            ClassUtils.isPresent(
                "org.springframework.boot.test.context.SpringBootTest",
                SchemaOwnershipGuard::class.java.classLoader,
            )
    }
}
