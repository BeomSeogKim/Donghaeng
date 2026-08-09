package com.donghaeng

import com.donghaeng.config.RequiredProfileMarker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
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

        // And it actually ran. Without this the opt-in could be silently inert —
        // a suite whose tests build no schema at all still passes today, because
        // there are no entities yet to notice.
        //
        // What this does NOT prove, verified rather than assumed: Flyway creates
        // the history table even when it finds zero migrations. The log of this
        // very test reads "No migrations found. Are your locations set up
        // correctly?" and then "Creating Schema History table". So the day #3's
        // SQL lands in the wrong location, this assertion stays green. Closing
        // that needs an assertion about applied migrations, which cannot be
        // written before a migration exists — it is an acceptance criterion on
        // #3, deliberately not pre-built here.
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("select count(*) from flyway_schema_history").use { rows ->
                    assertThat(rows.next()).isTrue()
                }
            }
        }
    }

    companion object {
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
