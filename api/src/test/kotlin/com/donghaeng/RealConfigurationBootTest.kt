package com.donghaeng

import com.donghaeng.config.RequiredProfileMarker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.flyway.FlywayProperties
import org.springframework.boot.autoconfigure.web.ErrorProperties
import org.springframework.boot.autoconfigure.web.ServerProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.core.io.ClassPathResource
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import javax.sql.DataSource

/**
 * Boots the committed configuration — `application.yml` plus one profile file —
 * with nothing overriding the datasource.
 *
 * The `@DynamicPropertySource` shape is load-bearing, and the precedence rule
 * is why. `@ServiceConnection` contributes a `JdbcConnectionDetails` bean that
 * outranks `spring.datasource.*` entirely, so a test written that way never
 * reads the committed properties — it stays green while `${DATABASE_URL}` is
 * broken, missing, or in the wrong URL dialect (issue #41). Here the
 * container's coordinates are published under the same three names production
 * supplies — `DATABASE_URL`, `DB_USERNAME`, `DB_PASSWORD` — so the placeholders
 * in `application.yml` are the code path under test.
 *
 * That is a rule about THIS test, not a rule against `@ServiceConnection`. The
 * repository Testcontainers tests arriving with #3/#7 want a working database,
 * not the committed configuration, and for them `@ServiceConnection` is the
 * right shape: bypassing `application.yml` is exactly what they should do. Use
 * it there, and keep it out of here.
 *
 * A wrong binding is not caught by the context loading: Boot builds the
 * `HikariDataSource` through setters and the pool only initialises on the first
 * `getConnection()`. What proves the binding is the connection this test opens
 * itself, and the assertion that the pool's URL is the container's — "the
 * placeholder resolved" and "the pool used what it resolved to" are two
 * different claims.
 *
 * `RANDOM_PORT` rather than the default MOCK: prod's `RemoteIpValve` and dev's
 * `server.address` are server settings, and MOCK starts no server, so under it
 * this test would not exercise the thing its name claims.
 *
 * One container is shared by both profiles: it is started here rather than by
 * `@Testcontainers`, which manages a container per test class.
 */
abstract class RealConfigurationBootTest(
    private val expectedProfile: String,
) {
    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var environment: Environment

    @Autowired
    private lateinit var serverProperties: ServerProperties

    @Autowired
    private lateinit var flywayProperties: FlywayProperties

    @Test
    fun `the committed configuration boots and reaches postgres`() {
        assertThat(environment.activeProfiles).containsExactly(expectedProfile)
        assertThat(environment.getProperty(RequiredProfileMarker.MARKER_PROPERTY)).isEqualTo(expectedProfile)

        // The value the yml placeholder resolved to, not a test override.
        assertThat(environment.getProperty("spring.datasource.url"))
            .startsWith("jdbc:postgresql://")
            .isEqualTo(postgres.jdbcUrl)

        dataSource.connection.use { connection ->
            assertThat(connection.metaData.databaseProductName).isEqualTo("PostgreSQL")
            // The pool connected to the resolved URL, not to something else.
            assertThat(connection.metaData.url).isEqualTo(postgres.jdbcUrl)
        }
    }

    @Test
    fun `the server error properties resolve to the values the base file pins`() {
        // The RESOLVED values, and that is the point of asserting them here as
        // well as in ProfileConfigurationTest's file sweep. The environment
        // outranks every yml (notes/2026-08-09-decision-schema-ownership.md), so
        // `SERVER_ERROR_INCLUDE_MESSAGE=always` typed into a deploy platform
        // reverses the pin with the whole suite green — and a test that reads
        // committed files provably cannot see it.
        //
        // Asserted on the bound ServerProperties rather than on the raw property
        // string, so relaxed binding and enum coercion are inside what is
        // checked.
        //
        // What this does NOT cover, stated so nobody deletes the tests that do:
        // it is not the masking guarantee. ProblemErrorController never reads
        // include-message or include-exception, and the masking of our own
        // responses is asserted by GlobalErrorHandlerTest and
        // ErrorDispatchContractTest. `include-stacktrace` is the one with reach
        // beyond this file — it gates Boot's hardening of Tomcat's error page —
        // and TomcatErrorPageHardening exists so that reach does not matter.
        assertThat(serverProperties.error.includeMessage).isEqualTo(ErrorProperties.IncludeAttribute.NEVER)
        assertThat(serverProperties.error.includeStacktrace).isEqualTo(ErrorProperties.IncludeAttribute.NEVER)
        assertThat(serverProperties.error.isIncludeException).isFalse()
    }

    @Test
    fun `the schema is validated, and only the suite runs Flyway`() {
        // Two halves of one arrangement (notes/2026-08-09-decision-schema-ownership.md).
        //
        // Half one: every environment that points at a real database resolves
        // `validate`, so a mapping that has drifted from the hand-applied schema
        // stops the app at boot instead of failing on a query in front of a user.
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate")

        // Half two: the committed yml disables Flyway for every environment, and
        // the suite opts back in — through a system property set in
        // build.gradle.kts, which no running app has. Asserting the source, not
        // just the value, is the point: read from `environment` alone this would
        // stay green if someone re-enabled Flyway in a profile file.
        assertThat(System.getProperty("spring.flyway.enabled")).isEqualTo("true")

        // And every migration file this build ships was applied. The assertion
        // is SET EQUALITY between the files and the history, and each half of
        // that is load-bearing for a different failure.
        //
        // Why not the table's existence: Flyway creates flyway_schema_history
        // even when it finds ZERO migrations — it logs "No migrations found.
        // Are your locations set up correctly?" and then "Creating Schema
        // History table" anyway.
        //
        // Why not a row count: `count > 0` decays the day V2 lands. A V2 typo'd
        // into `db/migrations` leaves V1 applied, so the count is 1, and 1 > 0
        // is green while half the schema silently is not the migration files'.
        // The criterion's letter would be met and its property gone.
        //
        // Why the files are enumerated from the CLASSPATH ROOT and not from
        // `db/migration`: globbing the correct directory cannot see a file that
        // went into the wrong one, which is the exact typo being defended
        // against. Walking the root finds it wherever it landed, and it then
        // shows up as expected-but-not-applied. That is why the WALK is rooted
        // at the classpath root — but it still has to be told where that root
        // is, and that anchor is a separate question.
        //
        // The anchor is Flyway's own configured location, walked up to its
        // classpath root. It is deliberately NOT `application.yml`, which is
        // what this used to be: that resolved to build/resources/main only
        // because src/test/resources does not exist, so adding a test
        // application.yml — an ordinary thing to do — would have pointed the
        // walk at build/resources/test, found zero migrations, and failed
        // `isNotEmpty()` pointing at nothing. It fails closed, but the repair a
        // maintainer reaches for from that message is "walk db/migration
        // directly", which silently deletes the wrong-directory property above.
        //
        // Reading the location from the property rather than hardcoding it
        // means renaming the directory moves the anchor with it; the walk
        // still starts a level above, so a stray file outside it is still seen.
        // The single-location assumption is asserted, not assumed.
        //
        // Nothing else in the suite would notice any of this — there are no
        // entities yet for `validate` to fail on.
        val location = flywayProperties.locations.single()
        assertThat(location).startsWith(CLASSPATH_LOCATION_PREFIX)
        val locationPath = location.removePrefix(CLASSPATH_LOCATION_PREFIX)
        val classpathRoot =
            locationPath.split("/").fold(ClassPathResource(locationPath).file) { directory, _ ->
                directory.parentFile
            }

        val migrationFileNames =
            classpathRoot
                .walkTopDown()
                .filter { it.isFile && MIGRATION_FILE_NAME.matches(it.name) }
                .map { it.name }
                .toList()

        // Guards the degenerate pass: with no files AND nothing applied, two
        // empty sets compare equal and the whole check evaporates — which is
        // precisely the state it exists to catch.
        assertThat(migrationFileNames).isNotEmpty()

        val expectedVersions =
            migrationFileNames.map { it.substringAfter("V").substringBefore("__").replace('_', '.') }

        val appliedVersions = mutableListOf<String>()
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                val applied =
                    "select version from flyway_schema_history " +
                        "where success = true and version is not null"
                statement.executeQuery(applied).use { rows ->
                    while (rows.next()) {
                        appliedVersions += rows.getString(1)
                    }
                }
            }
        }

        assertThat(appliedVersions).containsExactlyInAnyOrderElementsOf(expectedVersions)
    }

    companion object {
        // Flyway's own versioned-migration naming: V<version>__<description>.sql.
        private val MIGRATION_FILE_NAME = Regex("""^V.+__.+\.sql$""")

        // Flyway's prefix for a location resolved on the classpath rather than
        // the filesystem. Its own constant is internal to flyway-core.
        private const val CLASSPATH_LOCATION_PREFIX = "classpath:"

        private val postgres =
            PostgreSQLContainer("postgres:16-alpine").apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun productionShapedEnvironment(registry: DynamicPropertyRegistry) {
            registry.add("DATABASE_URL") { postgres.jdbcUrl }
            registry.add("DB_USERNAME") { postgres.username }
            registry.add("DB_PASSWORD") { postgres.password }
        }
    }
}

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class DevProfileBootTest : RealConfigurationBootTest("dev")

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("prod")
class ProdProfileBootTest : RealConfigurationBootTest("prod")
