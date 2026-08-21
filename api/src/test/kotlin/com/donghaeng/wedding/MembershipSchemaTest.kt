package com.donghaeng.wedding

import com.donghaeng.BaselineSchemaFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.postgresql.util.PSQLException
import java.sql.Connection

/**
 * What `membership` enforces about 한 사람은 웨딩 하나 in `V1__baseline_schema.sql`.
 *
 * The rule was the application's alone until 2026-08-21 — a read, then an insert,
 * serialised by an advisory lock (`WeddingService.claimSoleMembership`). It is now
 * also `ux_membership_user`, and the reason to assert it HERE rather than trust the
 * endpoint test is that the two hold different things: the endpoint test says
 * `POST /weddings` refuses, this says the row cannot exist — including when it is
 * written by a `#9`-era path, a fixture, or psql.
 *
 * **Uniqueness and the predicate, never just the name.** A `create index` where the
 * founder meant `create unique index` leaves an index with the right name doing
 * nothing, and `ddl-auto: validate` sees nothing about indexes at all (api/AGENTS.md,
 * Schema ownership). Every assertion below fails against the non-unique
 * `ix_membership_user` this replaced.
 *
 * See BaselineSchemaFixture for what "enforces" reaches: the file, not prod.
 */
internal class MembershipSchemaTest : BaselineSchemaFixture() {
    @Test
    fun `a second live membership for one person is not representable`() {
        rolledBack { connection ->
            val person = connection.insertUser()
            val ours = connection.insertWedding(person)
            val theirs = connection.insertWedding(person)

            connection.execute(membershipInsert(ours, person))

            // The whole rule, in the one form the application cannot bypass and
            // cannot forget. Before the index this insert succeeded, and the
            // person's ledger became whichever wedding `GET /weddings` sorted first
            // (notes/2026-08-21-decision-one-wedding-per-person.md §2).
            val second = connection.rejects(membershipInsert(theirs, person))

            assertThat(second.sqlState).isEqualTo(UNIQUE_VIOLATION)
            // The protocol field, not the message text — the same read
            // SoleMembershipCollision makes, so this binds its constant to the
            // schema and a rename in either place fails here.
            assertThat((second as PSQLException).serverErrorMessage?.constraint)
                .isEqualTo(SoleMembershipCollision.SOLE_MEMBERSHIP_INDEX)
        }
    }

    @Test
    fun `a person whose membership was revoked may belong to a wedding again`() {
        rolledBack { connection ->
            val person = connection.insertUser()
            val left = connection.insertWedding(person)
            val next = connection.insertWedding(person)
            connection.execute(membershipInsert(left, person))

            // PARTIAL, and this is the half a plain UNIQUE gets wrong. Every delete
            // here is soft (notes/2026-08-10-decision-soft-delete.md), so a person
            // removed from the wedding they were in keeps the row — and if it held
            // their slot they could never have a ledger again, with no way to
            // release it short of DDL.
            connection.execute("update membership set deleted_at = now() where wedding_id = $left")
            connection.execute(membershipInsert(next, person))

            assertThat(connection.selectLong("select count(*) from membership where user_id = $person")).isEqualTo(2)
            assertThat(
                connection.selectLong("select count(*) from membership where user_id = $person and deleted_at is null"),
            ).isOne()
        }
    }

    @Test
    fun `the couple are two accounts in one wedding, which this index must not forbid`() {
        rolledBack { connection ->
            // The rule narrowed is "one wedding per PERSON", never "one person per
            // wedding" — the couple share one ledger and are two accounts
            // (2026-08-21), which is what `#9`'s invite exists to produce. An index
            // on the wrong column, or on `(user_id)` without the predicate being the
            // only difference, would pass the test above and make the product
            // impossible.
            val groom = connection.insertUser()
            val bride = connection.insertUser()
            val wedding = connection.insertWedding(groom)

            connection.execute(membershipInsert(wedding, groom))
            connection.execute(membershipInsert(wedding, bride))

            assertThat(
                connection.selectLong("select count(*) from membership where wedding_id = $wedding and deleted_at is null"),
            ).isEqualTo(2)
        }
    }

    @Test
    fun `user_id is served by exactly one index, and it is the unique partial one`() {
        rolledBack { connection ->
            // REPLACED, not added beside: `ix_membership_user` was this index
            // without the `unique`, over the same column and the same predicate, and
            // keeping both would have cost a write on every membership row to buy
            // nothing. Asserted as "exactly one" rather than "the old name is gone",
            // so a third index over the same column also has to be argued for.
            assertThat(connection.indexesOn("membership", "user_id")).hasSize(1)

            // Read back rather than inferred from behaviour, for the property
            // behaviour cannot distinguish: an index that is unique over the right
            // column by accident of some other definition. This is the one line that
            // says the FILE says `unique`.
            assertThat(connection.selectText("select pg_get_indexdef('ux_membership_user'::regclass)"))
                .contains("CREATE UNIQUE INDEX")
                .contains("(user_id)")
                .contains("WHERE (deleted_at IS NULL)")
        }
    }

    private fun membershipInsert(
        weddingId: Long,
        userId: Long,
    ) = "insert into membership (wedding_id, user_id, created_at) values ($weddingId, $userId, now())"

    /**
     * Every index over [column] AND NOTHING ELSE, primary keys included, matched on
     * the rendered definition. A composite one is deliberately not counted: it
     * answers a different question and costs its own write anyway.
     */
    private fun Connection.indexesOn(
        table: String,
        column: String,
    ): List<String> =
        createStatement().use { statement ->
            statement
                .executeQuery(
                    """
                    select indexname
                    from pg_indexes
                    where schemaname = 'public'
                      and tablename = '$table'
                      and indexdef like '%($column)%'
                    """.trimIndent(),
                ).use { rows ->
                    generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
                }
        }

    private companion object {
        const val UNIQUE_VIOLATION = "23505"
    }
}
