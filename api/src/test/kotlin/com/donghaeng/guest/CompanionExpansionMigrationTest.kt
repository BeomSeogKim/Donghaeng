package com.donghaeng.guest

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager

/**
 * **`V6`'s expansion, run against rows that exist** — the half of that migration no
 * other test can reach.
 *
 * Every other schema test applies the migrations to an empty database, where the
 * `insert ... generate_series` at the heart of `V6` touches nothing and passes
 * vacuously. But this migration is not additive: it is typed by hand against a
 * database holding a real couple's 하객
 * (notes/2026-08-09-decision-schema-ownership.md), it MOVES data, and it then drops
 * the column the data came from. **A wrong expansion is not an error, it is a
 * different 식대 인원** — and 보증인원 is money.
 *
 * So this one builds a database up to the migration before this one, writes the rows
 * the couples already have, and then runs the rest — `V6` included — and reads the
 * ledger back.
 *
 * Its own container, because the shared one is already fully migrated and cannot be
 * rewound.
 */
internal class CompanionExpansionMigrationTest {
    @Test
    fun `a party of N becomes N records, named after the head and pointing at it`() {
        withDatabaseAt(BEFORE_THE_CHANGE) { connection, applyTheRest ->
            val userId = connection.insertUser()
            val weddingId = connection.insertWedding(userId)
            val alone = connection.insertGuest(weddingId, userId, "김영수", partySize = 1)
            val three = connection.insertGuest(weddingId, userId, "이영희", partySize = 3, attending = false)

            applyTheRest()

            // A party of one was already one record and is untouched.
            assertThat(connection.namesOf(alone)).isEmpty()
            assertThat(connection.select("select companion_of from guest where id = $alone")).containsExactly(null)

            // A party of three is two more records — N-1, since the head is the first
            // of the N — named from 1 and pointing at the head.
            assertThat(connection.namesOf(three)).containsExactly("이영희 동반 1", "이영희 동반 2")

            // **The head's 참석 travelled**, which is the rule that used to be free:
            // a party entered 불참 arrives 불참 and moves independently afterwards.
            assertThat(connection.select("select expected_attending from guest where companion_of = $three"))
                .containsOnly(false)

            // The 측 and the group travelled too, so no couple's aggregation moves
            // under them, and the head's clock travelled so the ledger's entry order
            // is unchanged.
            assertThat(connection.select("select side::text from guest where companion_of = $three")).containsOnly("GROOM")
            assertThat(connection.select("select group_category from guest where companion_of = $three")).containsOnly("FRIEND")
            val sameClock =
                "select created_at = (select created_at from guest g where g.id = $three) " +
                    "from guest where companion_of = $three"
            assertThat(connection.select(sameClock)).containsOnly(true)

            // 식대 인원 is now a count of records, and this is the number the couple
            // sees the morning after the migration: 1 + 0 (이영희's party is 불참).
            assertThat(connection.select("select count(*) from guest where expected_attending")).containsExactly(1L)
        }
    }

    @Test
    fun `a soft-deleted party is not expanded, and a head at the name bound still fits`() {
        withDatabaseAt(BEFORE_THE_CHANGE) { connection, applyTheRest ->
            val userId = connection.insertUser()
            val weddingId = connection.insertWedding(userId)
            val removed = connection.insertGuest(weddingId, userId, "박철수", partySize = 4, deleted = true)
            // A head whose own name already fills `varchar(100)`. Without the `left()`
            // in the migration this row alone aborts the whole thing, on a database
            // being changed by hand.
            val longest = connection.insertGuest(weddingId, userId, "가".repeat(100), partySize = 2)

            applyTheRest()

            // Expanding a deleted head would create LIVE companions of a 하객 the
            // couple removed.
            assertThat(connection.namesOf(removed)).isEmpty()

            // ` 동반 1` is five characters, so ninety-five of the head's remain.
            assertThat(connection.namesOf(longest)).containsExactly("가".repeat(95) + " 동반 1")
        }
    }

    private fun Connection.namesOf(headId: Long): List<Any?> = select("select name from guest where companion_of = $headId order by id")

    private fun Connection.insertGuest(
        weddingId: Long,
        userId: Long,
        name: String,
        partySize: Int,
        attending: Boolean = true,
        deleted: Boolean = false,
    ): Long =
        select(
            "insert into guest (wedding_id, name, side, group_category, expected_attending, expected_party_size, " +
                "created_by, created_at, updated_by, updated_at, deleted_at) " +
                "values ($weddingId, '$name', 'GROOM', 'FRIEND', $attending, $partySize, " +
                "$userId, now(), $userId, now(), ${if (deleted) "now()" else "null"}) returning id",
        ).single() as Long

    private fun Connection.insertUser(): Long =
        select("insert into app_user (name, created_at, updated_at) values ('테스터', now(), now()) returning id")
            .single() as Long

    private fun Connection.insertWedding(createdBy: Long): Long =
        select(
            "insert into wedding (wedding_date, created_by, created_at, updated_at) " +
                "values (date '2026-10-10', $createdBy, now(), now()) returning id",
        ).single() as Long

    private fun Connection.select(sql: String): List<Any?> =
        createStatement().use { statement ->
            statement.execute(sql)
            val rows = mutableListOf<Any?>()
            statement.resultSet?.use { while (it.next()) rows += it.getObject(1) }
            rows
        }

    private companion object {
        /**
         * The last version that exists before this change. It is a floor rather than
         * "V5": what the test needs is a database where `V6` has NOT run, and a
         * migration added between then and now must not silently move it.
         */
        const val BEFORE_THE_CHANGE = "4"

        /**
         * A container migrated up to [version] and no further, handed to [block] as an
         * open connection. Flyway's `target` is what makes "the state before this
         * change" reproducible from the files themselves rather than from a snapshot
         * somebody kept.
         */
        fun withDatabaseAt(
            version: String,
            block: (Connection, applyTheRest: () -> Unit) -> Unit,
        ) {
            PostgreSQLContainer("postgres:16-alpine").use { postgres ->
                postgres.start()
                val flyway = { Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password) }
                flyway().target(MigrationVersion.fromVersion(version)).load().migrate()
                DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                    block(connection) { flyway().load().migrate() }
                }
            }
        }
    }
}
