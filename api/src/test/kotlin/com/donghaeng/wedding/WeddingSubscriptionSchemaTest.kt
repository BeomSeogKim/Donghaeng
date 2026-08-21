package com.donghaeng.wedding

import com.donghaeng.BaselineSchemaFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.postgresql.util.PSQLException
import java.sql.Connection

/**
 * What `wedding_subscription` enforces in `V3__wedding_core.sql`.
 *
 * **웨딩당 활성 구독 1건 lives in an index and not in a service check**
 * (`notes/2026-08-22-decision-entitlement-belongs-to-the-wedding.md` §4), so two tabs,
 * a double-tapped button and two racing renewals all end in one term however the
 * requests interleave. `ddl-auto: validate` sees nothing about indexes, constraints or
 * nullability, so these assertions are the only thing that says the file still holds
 * that — and this is money.
 *
 * The half a behaviour test cannot reach is asserted here too: **there is no
 * `deleted_at` on this table.** A term is never removed, it ends. A column added
 * "for consistency" with the rest of the schema would make a paid stretch of time
 * erasable, and "who paid for July" is the one question this table exists to answer.
 *
 * See BaselineSchemaFixture for what "enforces" reaches: the file, not prod.
 */
internal class WeddingSubscriptionSchemaTest : BaselineSchemaFixture() {
    @Test
    fun `a second live term for one wedding is not representable`() {
        rolledBack { connection ->
            val person = connection.insertUser()
            val wedding = connection.insertWedding(person)
            connection.execute(liveTerm(wedding))

            val second = connection.rejects(liveTerm(wedding))

            assertThat(second.sqlState).isEqualTo(UNIQUE_VIOLATION)
            assertThat((second as PSQLException).serverErrorMessage?.constraint).isEqualTo(LIVE_TERM_INDEX)
        }
    }

    @Test
    fun `an ended term does not occupy the slot, and is not removed to free it`() {
        rolledBack { connection ->
            // The handover, at the level of rows: end the live term, open the next.
            // A plain `unique (wedding_id)` could not have done this — it would have
            // forced the first term to be deleted or overwritten, and then 신랑's
            // stretch would be gone the moment 신부 took over
            // (the record, §3).
            val payer = connection.insertUser()
            val wedding = connection.insertWedding(payer)
            connection.execute(liveTerm(wedding))

            connection.execute("update wedding_subscription set ended_at = now() where wedding_id = $wedding and ended_at is null")
            connection.execute(liveTerm(wedding, payerId = payer))

            assertThat(connection.selectLong("select count(*) from wedding_subscription where wedding_id = $wedding")).isEqualTo(2)
            assertThat(
                connection.selectLong("select count(*) from wedding_subscription where wedding_id = $wedding and ended_at is null"),
            ).isOne()
        }
    }

    @Test
    fun `a term that ends before it starts is refused by the schema, not by a validator`() {
        rolledBack { connection ->
            val person = connection.insertUser()
            val wedding = connection.insertWedding(person)

            val backwards =
                connection.rejects(
                    "insert into wedding_subscription (wedding_id, plan, status, started_at, ended_at, created_at, updated_at) " +
                        "values ($wedding, 'FREE', 'ACTIVE', now(), now() - interval '1 day', now(), now())",
                )

            assertThat(backwards.sqlState).isEqualTo(CHECK_VIOLATION)
            assertThat((backwards as PSQLException).serverErrorMessage?.constraint).isEqualTo("ck_subscription_term_order")

            // Ending exactly when it started is legal — a term handed over the
            // instant it opened is odd, not meaningless, and `>=` is what the file
            // says.
            connection.execute(
                "insert into wedding_subscription (wedding_id, plan, status, started_at, ended_at, created_at, updated_at) " +
                    "select $wedding, 'FREE', 'ACTIVE', t, t, t, t from (select now() as t) as clock",
            )
        }
    }

    @Test
    fun `a free term names no payer and no period end, and the columns allow it`() {
        rolledBack { connection ->
            // What a FREE term IS: nobody paid, so `payer_id` is NULL, and no money
            // covers a period, so `current_period_end` is NULL. Both are nullable in
            // the file and nullability is invisible to `ddl-auto: validate`, so a
            // hand-typed `not null` on either would first be met by
            // `WeddingService.create` — at the moment a couple signs up.
            val person = connection.insertUser()
            val wedding = connection.insertWedding(person)

            connection.execute(liveTerm(wedding))

            assertThat(
                connection.selectLong(
                    "select count(*) from wedding_subscription " +
                        "where wedding_id = $wedding and payer_id is null and current_period_end is null and ended_at is null",
                ),
            ).isOne()
        }
    }

    @Test
    fun `the live-term index is unique and partial, and the table has no deleted_at`() {
        rolledBack { connection ->
            // Read back rather than inferred from behaviour: a plain
            // `unique (wedding_id)` refuses the second insert above too, and would
            // then also refuse every renewal a wedding ever has.
            assertThat(connection.selectText("select pg_get_indexdef('$LIVE_TERM_INDEX'::regclass)"))
                .contains("CREATE UNIQUE INDEX")
                .contains("(wedding_id)")
                .contains("ended_at IS NULL")

            assertThat(connection.columnsOf("wedding_subscription"))
                .describedAs("a term ends, it is never removed — see this class's comment")
                .doesNotContain("deleted_at")
                .contains("plan", "status", "payer_id", "started_at", "ended_at", "current_period_end")
        }
    }

    private fun liveTerm(
        weddingId: Long,
        plan: String = "FREE",
        payerId: Long? = null,
    ) = "insert into wedding_subscription (wedding_id, plan, status, payer_id, started_at, created_at, updated_at) " +
        "values ($weddingId, '$plan', 'ACTIVE', ${payerId ?: "null"}, now(), now(), now())"

    private fun Connection.columnsOf(table: String): List<String> =
        createStatement().use { statement ->
            statement
                .executeQuery("select column_name from information_schema.columns where table_schema = 'public' and table_name = '$table'")
                .use { rows -> generateSequence { if (rows.next()) rows.getString(1) else null }.toList() }
        }

    private companion object {
        const val UNIQUE_VIOLATION = "23505"
        const val CHECK_VIOLATION = "23514"
        const val LIVE_TERM_INDEX = "ux_subscription_live"
    }
}
