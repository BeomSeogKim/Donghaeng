package com.donghaeng

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.env.PropertiesPropertySourceLoader
import org.springframework.boot.env.PropertySourceLoader
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.io.FileSystemResource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence

/**
 * Configuration has no other failure detector: nothing else breaks when a
 * profile file drifts. Two different kinds of check live here —
 *
 * - a structural sweep over every `application*` file on the resource tree,
 *   which is the one that has to hold for files that do not exist yet;
 * - assertions on named keys in the three files that exist today, which pin the
 *   specific decisions of issue #50.
 */
class ProfileConfigurationTest {
    private val resourceRoot = Path.of("src/main/resources")

    private fun configFiles(): List<Path> =
        Files
            .walk(resourceRoot)
            .asSequence()
            .filter { Files.isRegularFile(it) }
            .filter { it.fileName.toString().matches(Regex("""application.*\.(yml|yaml|properties)""")) }
            .sortedBy { it.toString() }
            .toList()

    /** Every document in the file — `.single()` would throw the day one uses `---`. */
    private fun load(path: Path): List<EnumerablePropertySource<*>> {
        val loader: PropertySourceLoader =
            if (path.toString().endsWith(".properties")) {
                PropertiesPropertySourceLoader()
            } else {
                YamlPropertySourceLoader()
            }
        return loader
            .load(path.toString(), FileSystemResource(path))
            .filterIsInstance<EnumerablePropertySource<*>>()
    }

    private fun properties(path: Path): Map<String, Any?> =
        load(path).flatMap { source -> source.propertyNames.map { it to source.getProperty(it) } }.toMap()

    private fun properties(fileName: String): Map<String, Any?> = properties(resourceRoot.resolve(fileName))

    private val base = properties("application.yml")
    private val dev = properties("application-dev.yml")
    private val prod = properties("application-prod.yml")

    // --- structural sweep -------------------------------------------------

    @Test
    fun `the sweep actually finds the configuration files`() {
        // Without this, a broken glob makes every sweep below pass vacuously.
        assertThat(configFiles().map { it.fileName.toString() })
            .contains("application.yml", "application-dev.yml", "application-prod.yml")
    }

    @Test
    fun `every value referencing the environment is a bare placeholder`() {
        // Asserted on values, not on key names: the leak this repo has a record
        // about hid inside `spring.datasource.url`, whose name says nothing.
        // A `${VAR:default}` segment is rejected too — that is where a literal
        // gets inlined while still looking like an environment reference.
        val bare = Regex("""^\$\{[A-Z0-9_]+}$""")
        configFiles().forEach { path ->
            properties(path).forEach { (name, value) ->
                val text = value?.toString() ?: return@forEach
                if (text.contains("\${")) {
                    assertThat(text)
                        .describedAs("%s · %s is an environment reference with no inline default", path, name)
                        .matches(bare.pattern)
                }
            }
        }
    }

    @Test
    fun `no value carries a credential inside a URI`() {
        // `postgresql://user:pw@host/db` — the documented shape
        // (notes/2026-08-08-decision-scaffold-secrets-and-surface.md).
        val credentialUri = Regex("""://[^/\s:]+:[^/\s@]+@""")
        configFiles().forEach { path ->
            properties(path).forEach { (name, value) ->
                assertThat(value?.toString() ?: "")
                    .describedAs("%s · %s embeds credentials in a URI", path, name)
                    .doesNotMatch { credentialUri.containsMatchIn(it) }
            }
        }
    }

    // --- the decisions of issue #50 --------------------------------------

    @Test
    fun `no environment logs SQL or its bound values`() {
        // In the base, so it holds for a mistyped profile too, and because
        // `show-sql: false` is a different code path from these three loggers.
        // Comparing to the string 'OFF' is what catches an unquoted OFF, which
        // YAML 1.1 parses as the boolean false.
        listOf(base, dev, prod).forEach { source ->
            assertThat(source["spring.jpa.show-sql"]).isNotEqualTo(true)
            assertThat(source["logging.level.org.hibernate.SQL"]).isIn(null, "OFF")
            assertThat(source["logging.level.org.hibernate.orm.jdbc.bind"]).isIn(null, "OFF")
            assertThat(source["logging.level.org.hibernate.orm.jdbc.extract"]).isIn(null, "OFF")
        }
        assertThat(base["spring.jpa.show-sql"]).isEqualTo(false)
        assertThat(base["logging.level.org.hibernate.SQL"]).isEqualTo("OFF")
        assertThat(base["logging.level.org.hibernate.orm.jdbc.bind"]).isEqualTo("OFF")
        assertThat(base["logging.level.org.hibernate.orm.jdbc.extract"]).isEqualTo("OFF")
    }

    @Test
    fun `no environment loosens what the base pins`() {
        // The base pins these three, but a profile file outranks the base, so
        // pinning alone stops nothing — the same reason the SQL-logging test
        // above sweeps rather than asserting on `base` only. Two of them are
        // the ones whose wrong value destroys data: `ddl-auto` on a profile
        // pointed at a real database, and `clean-disabled: false` in `dev`,
        // which points at the founder's shared local Postgres.
        //
        // This is also the guard on the cheapest way out of a red job: when a
        // future CI failure reads "schema does not match", `ddl-auto: update`
        // makes it green and takes the schema away from its owner.
        configFiles().forEach { path ->
            val source = properties(path)

            assertThat(source["spring.jpa.hibernate.ddl-auto"])
                .describedAs("%s · ddl-auto must not create or mutate the schema", path)
                .isIn(null, "none", "validate")

            // Both flags below are whitelists of safe values, not `isNotEqualTo`
            // of the unsafe one, and the quoting is why: YAML loads `true` as a
            // Boolean and `"true"` as the String "true", while Spring's relaxed
            // binding turns Flyway on for either. `isNotEqualTo(true)` therefore
            // passes on five quote characters. Same shape as `ddl-auto` above —
            // name what is allowed, so an unrecognised value is red by default.
            assertThat(source["spring.flyway.clean-disabled"])
                .describedAs("%s · flyway clean must stay disabled", path)
                .isIn(null, true, "true")

            // Flyway is a restrictive-base setting now, for the same reason as
            // the two above: a migration running unattended at startup is
            // irreversible work against a real database
            // (notes/2026-08-09-decision-schema-ownership.md). The suite opts in
            // through a system property set in build.gradle.kts, never here — so
            // no environment file may turn it on, not even dev.
            assertThat(source["spring.flyway.enabled"])
                .describedAs("%s · only the tests may run Flyway", path)
                .isIn(null, false, "false")

            // dev is the one profile that generates the OpenAPI document, and
            // it binds loopback to do it (asserted below).
            if (!path.fileName.toString().startsWith("application-dev.")) {
                assertThat(source["springdoc.api-docs.enabled"])
                    .describedAs("%s · no machine-readable introspection surface outside dev", path)
                    .isNotEqualTo(true)
            }
        }
    }

    @Test
    fun `every environment with a real database validates its mapping against it`() {
        // The counterpart to Flyway being off outside the tests: the schema the
        // suite builds from the migration files and the schema someone typed in
        // by hand are two things, and `validate` is the only thing that ever
        // compares them (notes/2026-08-09-decision-schema-ownership.md). The
        // base stays `none` — an environment that has declared nothing has
        // nothing to validate against.
        assertThat(dev["spring.jpa.hibernate.ddl-auto"]).isEqualTo("validate")
        assertThat(prod["spring.jpa.hibernate.ddl-auto"]).isEqualTo("validate")
    }

    @Test
    fun `the base never logs request details`() {
        // Cookie header, i.e. the session token.
        assertThat(base["spring.mvc.log-request-details"]).isEqualTo(false)
    }

    @Test
    fun `the base is the restrictive one and pins no profile of its own`() {
        assertThat(base["springdoc.api-docs.enabled"]).isEqualTo(false)
        assertThat(base["spring.jpa.hibernate.ddl-auto"]).isEqualTo("none")
        assertThat(base["spring.flyway.clean-disabled"]).isEqualTo(true)
        assertThat(base["spring.flyway.enabled"]).isEqualTo(false)
        assertThat(base["spring.profiles.active"]).isNull()
        assertThat(base["spring.profiles.default"]).isNull()
    }

    @Test
    fun `only the profile files define the marker that makes a profile mandatory`() {
        assertThat(base["donghaeng.profile"]).isNull()
        assertThat(dev["donghaeng.profile"]).isEqualTo("dev")
        assertThat(prod["donghaeng.profile"]).isEqualTo("prod")
    }

    @Test
    fun `prod restates the flyway settings, trusts forwarded headers, publishes nothing`() {
        // Restated in the file rather than inherited, so a deploy review reads
        // them where it is already looking; pinned here so the restatement
        // cannot quietly rot away.
        assertThat(prod["spring.flyway.enabled"]).isEqualTo(false)
        assertThat(prod["spring.flyway.clean-disabled"]).isEqualTo(true)
        assertThat(prod["server.forward-headers-strategy"]).isEqualTo("native")
        assertThat(prod["springdoc.api-docs.enabled"]).isNotEqualTo(true)
    }

    @Test
    fun `dev opens the API document only on loopback`() {
        assertThat(dev["springdoc.api-docs.enabled"]).isEqualTo(true)
        assertThat(dev["server.address"]).isEqualTo("127.0.0.1")
        assertThat(dev["logging.level.com.donghaeng"]).isEqualTo("DEBUG")
    }
}
