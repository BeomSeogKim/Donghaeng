package com.donghaeng

import org.flywaydb.core.Flyway
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * A real Postgres 16 with `V1__baseline_schema.sql` applied by Flyway, for the
 * tests that assert what the baseline schema ENFORCES rather than what it says.
 *
 * WHAT THESE TESTS PIN, and it is narrower than it looks: they pin THE FILE.
 * They cannot pin any real database, because no real database is built from
 * this file — every DDL statement outside the suite is typed by hand
 * (notes/2026-08-09-decision-schema-ownership.md), and `ddl-auto: validate`
 * compares JDBC type codes only, so a constraint skipped while typing is
 * invisible to it. Applying these same migrations to CI's throwaway Postgres is
 * #55; asserting the deployed database matches is nobody's yet. What the file
 * being green buys is that the manuscript the founder types from is correct and
 * stays correct.
 *
 * No Spring context: the subject is DDL, and JDBC reaches it directly. This is
 * deliberately not the shape the repository tests will take once entities
 * exist — those want `@ServiceConnection` and a context.
 *
 * One container for every subclass, since the companion belongs to this base
 * class rather than to each of them. Every test therefore runs inside a
 * transaction that is rolled back, so the schema stays clean between them
 * without a truncate step.
 */
internal abstract class BaselineSchemaFixture {
    /**
     * Runs [block] in a transaction and always rolls it back. Nothing a test
     * writes survives it, including the rows written before a deliberate
     * violation.
     */
    protected fun rolledBack(block: (Connection) -> Unit) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.autoCommit = false
            try {
                block(connection)
            } finally {
                connection.rollback()
            }
        }
    }

    /**
     * Executes [sql] expecting the schema to reject it, and hands back the
     * rejection so a test can assert WHICH constraint fired.
     *
     * The savepoint is what makes several of these usable in one test: a failed
     * statement puts a Postgres transaction into an aborted state where every
     * later statement fails with 25P02, which would make the second assertion
     * in a test pass for the wrong reason.
     */
    protected fun Connection.rejects(sql: String): SQLException {
        val savepoint = setSavepoint()
        try {
            createStatement().use { it.executeUpdate(sql) }
        } catch (rejection: SQLException) {
            rollback(savepoint)
            return rejection
        }
        rollback(savepoint)
        throw AssertionError("the schema accepted a statement it should have rejected: $sql")
    }

    protected fun Connection.selectLong(sql: String): Long =
        createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                check(rows.next()) { "no row for: $sql" }
                rows.getLong(1)
            }
        }

    protected fun Connection.selectText(sql: String): String =
        createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                check(rows.next()) { "no row for: $sql" }
                rows.getString(1)
            }
        }

    protected fun Connection.selectBoolean(sql: String): Boolean =
        createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                check(rows.next()) { "no row for: $sql" }
                rows.getBoolean(1)
            }
        }

    protected fun Connection.insertReturningId(sql: String): Long = selectLong("$sql returning id")

    protected fun Connection.execute(sql: String) {
        createStatement().use { it.executeUpdate(sql) }
    }

    protected fun Connection.insertUser(): Long =
        insertReturningId("insert into app_user (name, created_at, updated_at) values ('테스터', now(), now())")

    protected fun Connection.insertWedding(createdBy: Long): Long =
        insertReturningId(
            "insert into wedding (wedding_date, groom_name, bride_name, created_by, created_at, updated_at) " +
                "values (date '2026-10-10', '신랑', '신부', $createdBy, now(), now())",
        )

    companion object {
        private val postgres =
            PostgreSQLContainer("postgres:16-alpine").apply {
                start()
                Flyway
                    .configure()
                    .dataSource(jdbcUrl, username, password)
                    .load()
                    .migrate()
            }
    }
}
