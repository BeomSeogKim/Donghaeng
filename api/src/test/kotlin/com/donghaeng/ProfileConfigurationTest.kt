package com.donghaeng

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.io.ClassPathResource

/**
 * The profile files are configuration, so nothing else fails when one of them
 * drifts. These assertions are the failure detector: the base file must be the
 * restrictive one, and prod must never grow a SQL log line — bound parameters
 * carry guest contacts (notes/2026-07-30-decision-network-security.md).
 */
class ProfileConfigurationTest {
    private fun load(fileName: String): EnumerablePropertySource<*> {
        val resource = ClassPathResource(fileName)
        assertThat(resource.exists()).describedAs("$fileName exists").isTrue()
        return YamlPropertySourceLoader().load(fileName, resource).single() as EnumerablePropertySource<*>
    }

    private val base = load("application.yml")
    private val local = load("application-local.yml")
    private val prod = load("application-prod.yml")

    @Test
    fun `the base profile is the restrictive one`() {
        assertThat(base.getProperty("springdoc.api-docs.enabled")).isEqualTo(false)
        assertThat(base.getProperty("spring.jpa.show-sql")).isEqualTo(false)
        assertThat(base.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("none")
        assertThat(base.getProperty("spring.flyway.clean-disabled")).isEqualTo(true)
    }

    @Test
    fun `the base profile does not pin an active profile`() {
        assertThat(base.getProperty("spring.profiles.active")).isNull()
        assertThat(base.getProperty("spring.profiles.default")).isNull()
    }

    @Test
    fun `prod never logs SQL and never publishes the API document`() {
        assertThat(prod.getProperty("spring.jpa.show-sql")).isNotEqualTo(true)
        assertThat(prod.getProperty("springdoc.api-docs.enabled")).isNotEqualTo(true)
        assertThat(prod.getProperty("logging.level.org.hibernate.SQL")).isEqualTo("OFF")
        assertThat(prod.getProperty("logging.level.org.hibernate.orm.jdbc.bind")).isEqualTo("OFF")
    }

    @Test
    fun `prod disables flyway clean and trusts only the proxy for forwarded headers`() {
        assertThat(prod.getProperty("spring.flyway.clean-disabled")).isEqualTo(true)
        assertThat(prod.getProperty("server.forward-headers-strategy")).isEqualTo("native")
    }

    @Test
    fun `local loosens only what development needs`() {
        assertThat(local.getProperty("springdoc.api-docs.enabled")).isEqualTo(true)
        assertThat(local.getProperty("logging.level.com.donghaeng")).isEqualTo("DEBUG")
        // Even on the founder's Mac: bound parameters are guest contacts, and a
        // habit formed locally is the one that gets copied into prod.
        assertThat(local.getProperty("spring.jpa.show-sql")).isNotEqualTo(true)
        assertThat(local.getProperty("logging.level.org.hibernate.orm.jdbc.bind")).isNull()
    }

    @Test
    fun `no yml carries a credential`() {
        mapOf("application.yml" to base, "application-local.yml" to local, "application-prod.yml" to prod)
            .forEach { (fileName, source) ->
                source.propertyNames
                    .filter { it.contains("password") || it.contains("secret") || it.contains("username") }
                    .forEach { name ->
                        assertThat(source.getProperty(name).toString())
                            .describedAs("$fileName · $name is an environment reference, not a value")
                            .startsWith("\${")
                    }
            }
    }
}
