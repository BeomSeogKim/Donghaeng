package com.donghaeng

import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.containers.PostgreSQLContainer

/**
 * A Postgres for the tests that boot the WHOLE application but are not about the
 * database — the error contract, the malformed-path guard, the Tomcat error page.
 *
 * Those three used to exclude `DataSourceAutoConfiguration`,
 * `HibernateJpaAutoConfiguration` and `FlywayAutoConfiguration`, on the honest
 * reasoning that no database is involved in what they assert. That worked while
 * `api/src/main` had no domain code. It stopped working at the first repository-
 * backed bean (`#37`): the application context can no longer be built without a
 * DataSource, and the alternative — pruning whichever beans a stop happened to add
 * — is a maintenance tax paid by every future stop, in a file whose subject is
 * something else entirely.
 *
 * So the exclusions are gone and these tests boot the real application. That is a
 * strengthening rather than a compromise, and Spring Security is why: its filter
 * chain registers for the `ERROR` dispatch, so `ErrorDispatchContractTest` is now
 * observing the chain the deployed application actually runs — which is what
 * notes/2026-08-10-decision-auth-gate-and-sequence.md meant by "`#6` is what
 * finally puts real code on that path".
 *
 * One container for all of them, started once. `ddl-auto` resolves to the base
 * file's `none` in these tests (they set `donghaeng.profile` directly rather than
 * activating a profile), so nothing here validates a mapping; Flyway still builds
 * the schema, because the suite is the one place it runs.
 */
internal object SharedPostgres {
    private val container = PostgreSQLContainer("postgres:16-alpine").apply { start() }

    /**
     * Published under the three names production supplies, so `application.yml`'s
     * placeholders stay on the code path — the same reason
     * `RealConfigurationBootTest` does not use `@ServiceConnection`.
     */
    fun publish(registry: DynamicPropertyRegistry) {
        registry.add("DATABASE_URL") { container.jdbcUrl }
        registry.add("DB_USERNAME") { container.username }
        registry.add("DB_PASSWORD") { container.password }
    }
}
