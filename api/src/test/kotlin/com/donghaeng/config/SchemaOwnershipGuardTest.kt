package com.donghaeng.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.boot.diagnostics.FailureAnalyzer
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.io.support.SpringFactoriesLoader
import org.springframework.mock.env.MockEnvironment

/**
 * Every other guard in this stop reads the committed yml files, and none of them
 * can see the thing that actually outranks those files: `SPRING_FLYWAY_ENABLED=true`
 * or `SPRING_JPA_HIBERNATE_DDL_AUTO=create-drop` typed into a deploy platform
 * after a red boot. This one reads the RESOLVED value, which is the only place
 * an environment variable and a yml key are the same fact
 * (notes/2026-08-09-decision-schema-ownership.md).
 *
 * `underTest = false` throughout: the guard is deliberately inert on the test
 * classpath, where Flyway legitimately runs, and the last test here is the one
 * that pins that scoping.
 */
class SchemaOwnershipGuardTest {
    private val guard = SchemaOwnershipGuard(underTest = false)

    private fun environmentOf(vararg properties: Pair<String, String>) =
        MockEnvironment().apply { properties.forEach { (name, value) -> setProperty(name, value) } }

    private fun refuses(vararg properties: Pair<String, String>) =
        assertThatThrownBy { guard.postProcessEnvironment(environmentOf(*properties), SpringApplication()) }
            .isInstanceOf(SchemaOwnershipViolationException::class.java)

    private fun starts(vararg properties: Pair<String, String>) =
        assertThatCode { guard.postProcessEnvironment(environmentOf(*properties), SpringApplication()) }
            .doesNotThrowAnyException()

    // --- Flyway ------------------------------------------------------------

    @Test
    fun `an environment that switches Flyway back on refuses to start`() {
        // The founder's scenario exactly: one environment variable, typed once,
        // outranking every committed file in the jar.
        refuses(FLYWAY to "true", DDL_AUTO to "validate")
    }

    @Test
    fun `a Flyway value that only Spring's relaxed binding reads as on refuses too`() {
        // `on`, `yes` and `1` are all true to Spring's Boolean conversion, so a
        // check for the literal string "true" would be walked past by three
        // characters. Whitelist "false" instead of blacklisting "true".
        refuses(FLYWAY to "on", DDL_AUTO to "validate")
        refuses(FLYWAY to "yes", DDL_AUTO to "validate")
        refuses(FLYWAY to "1", DDL_AUTO to "validate")
        refuses(FLYWAY to "TRUE", DDL_AUTO to "validate")
    }

    @Test
    fun `an absent Flyway setting refuses, because Boot defaults it ON`() {
        // The asymmetry that makes "absent means safe" wrong here: Boot's
        // FlywayProperties defaults `enabled` to true, so a base file that lost
        // its line reads as silence and behaves as a migration at startup.
        refuses(DDL_AUTO to "validate")
    }

    // --- Hibernate DDL -----------------------------------------------------

    @Test
    fun `an environment that lets Hibernate write DDL refuses to start`() {
        refuses(FLYWAY to "false", DDL_AUTO to "create-drop")
        refuses(FLYWAY to "false", DDL_AUTO to "update")
        refuses(FLYWAY to "false", DDL_AUTO to "create")
    }

    @Test
    fun `the raw Hibernate property is checked too, not just Boot's alias`() {
        // `spring.jpa.properties.hibernate.hbm2ddl.auto` is not a synonym that
        // loses: HibernateProperties.determineDdlAuto reads it FIRST and only
        // falls back to `spring.jpa.hibernate.ddl-auto`. Checking the alias
        // alone leaves a second environment variable that wins outright.
        refuses(FLYWAY to "false", DDL_AUTO to "validate", HBM2DDL to "create-drop")
    }

    @Test
    fun `an absent ddl-auto refuses, because its default is the database's to decide`() {
        // Boot picks `create-drop` for an embedded database and `none`
        // otherwise, so absence resolves through database detection rather than
        // through anything anyone wrote down. The base file always states it.
        refuses(FLYWAY to "false")
    }

    // --- the values a deploy is allowed to hold ----------------------------

    @Test
    fun `validate against a hand-applied schema starts`() {
        starts(FLYWAY to "false", DDL_AUTO to "validate")
    }

    @Test
    fun `none starts, and the raw property may restate a safe value`() {
        starts(FLYWAY to "false", DDL_AUTO to "none")
        starts(FLYWAY to "false", DDL_AUTO to "validate", HBM2DDL to "validate")
    }

    // --- scoping -----------------------------------------------------------

    @Test
    fun `the guard is inert on the test classpath, where Flyway legitimately runs`() {
        // Default constructor — the one spring.factories uses — under the suite,
        // whose JVM has both the test classpath and `spring.flyway.enabled=true`
        // from build.gradle.kts. If this ever throws, every Spring test in the
        // repo fails; asserting it here says WHY, and goes red if the classpath
        // probe stops recognising a test JVM.
        assertThatCode {
            SchemaOwnershipGuard().postProcessEnvironment(
                environmentOf(FLYWAY to "true", DDL_AUTO to "create-drop"),
                SpringApplication(),
            )
        }.doesNotThrowAnyException()
    }

    // --- wiring ------------------------------------------------------------

    @Test
    fun `the guard is registered as an EnvironmentPostProcessor, not as a bean`() {
        // Same reason as RequiredProfileMarker: as a bean it would run after the
        // EntityManagerFactory had been built, which is after Hibernate has
        // already done whatever `ddl-auto` told it to.
        val registered =
            SpringFactoriesLoader
                .forDefaultResourceLocation(javaClass.classLoader)
                .load(
                    EnvironmentPostProcessor::class.java,
                    null,
                    SpringFactoriesLoader.FailureHandler { _, _, _ -> },
                )

        assertThat(registered).hasAtLeastOneElementOfType(SchemaOwnershipGuard::class.java)
    }

    @Test
    fun `the failure names the fix rather than showing a stack trace`() {
        val analyzer: FailureAnalyzer = SchemaOwnershipFailureAnalyzer()
        val analysis = analyzer.analyze(SchemaOwnershipViolationException("$FLYWAY resolved to `true`"))

        assertThat(analysis).isNotNull
        assertThat(analysis!!.description).contains("$FLYWAY resolved to `true`")
        assertThat(analysis.action).contains("$FLYWAY=false")
        assertThat(analysis.action).contains("$DDL_AUTO=validate")
    }

    private companion object {
        const val FLYWAY = "spring.flyway.enabled"
        const val DDL_AUTO = "spring.jpa.hibernate.ddl-auto"
        const val HBM2DDL = "spring.jpa.properties.hibernate.hbm2ddl.auto"
    }
}
