package com.donghaeng.wedding

import com.donghaeng.BaselineSchemaFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.postgresql.util.PSQLException
import java.sql.Connection

/**
 * What `wedding_party` enforces about the couple's two seats in `V3__wedding_core.sql`.
 *
 * **This is `MembershipSchemaTest` re-pointed, not a new test** (2026-08-22). The rule
 * it holds — 한 사람은 웨딩 하나 — did not change when `membership` became
 * `wedding_party`; `ux_membership_user` became `ux_party_user` with one new clause,
 * `user_id is not null`, which is what makes an unclaimed seat legal. The reason to
 * assert it HERE rather than trust the endpoint test is unchanged too: the two hold
 * different things, in that the endpoint test says `POST /weddings` refuses and this
 * says the row cannot exist — including when it is written by a `#9`-era path, a
 * fixture, or psql.
 *
 * **Uniqueness and the predicate, never just the name.** A `create index` where the
 * founder meant `create unique index` leaves an index with the right name doing
 * nothing, and `ddl-auto: validate` sees nothing about indexes at all (api/AGENTS.md,
 * Schema ownership).
 *
 * See BaselineSchemaFixture for what "enforces" reaches: the file, not prod.
 */
internal class WeddingSeatSchemaTest : BaselineSchemaFixture() {
    @Test
    fun `a second live seat for one person is not representable`() {
        rolledBack { connection ->
            val person = connection.insertUser()
            val ours = connection.insertWedding(person)
            val theirs = connection.insertWedding(person)

            connection.execute(seatInsert(ours, "GROOM", person))

            // The whole rule, in the one form the application cannot bypass and
            // cannot forget. Before the index this insert succeeded, and the
            // person's ledger became whichever wedding `GET /weddings` sorted first
            // (notes/2026-08-21-decision-one-wedding-per-person.md §2).
            val second = connection.rejects(seatInsert(theirs, "GROOM", person))

            assertThat(second.sqlState).isEqualTo(UNIQUE_VIOLATION)
            // The protocol field, not the message text — the same read
            // SoleSeatCollision makes, so this binds its constant to the schema and
            // a rename in either place fails here.
            assertThat((second as PSQLException).serverErrorMessage?.constraint)
                .isEqualTo(SoleSeatCollision.SOLE_SEAT_INDEX)
        }
    }

    @Test
    fun `a person whose seat was released may hold one again`() {
        rolledBack { connection ->
            val person = connection.insertUser()
            val left = connection.insertWedding(person)
            val next = connection.insertWedding(person)
            connection.execute(seatInsert(left, "GROOM", person))

            // PARTIAL, and this is the half a plain UNIQUE gets wrong. Every delete
            // here is soft (notes/2026-08-10-decision-soft-delete.md), so a person
            // whose seat was released keeps the row — and if it held their slot they
            // could never have a ledger again, with no way to release it short of
            // DDL. `deleted_at` is on the seat FOR this
            // (notes/2026-08-22-decision-the-couples-two-seats.md §1).
            connection.execute("update wedding_party set deleted_at = now() where wedding_id = $left")
            connection.execute(seatInsert(next, "GROOM", person))

            assertThat(connection.selectLong("select count(*) from wedding_party where user_id = $person")).isEqualTo(2)
            assertThat(
                connection.selectLong("select count(*) from wedding_party where user_id = $person and deleted_at is null"),
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

            connection.execute(seatInsert(wedding, "GROOM", groom))
            connection.execute(seatInsert(wedding, "BRIDE", bride))

            assertThat(
                connection.selectLong("select count(*) from wedding_party where wedding_id = $wedding and deleted_at is null"),
            ).isEqualTo(2)
        }
    }

    @Test
    fun `a waiting seat carries a side and nothing else, and several may wait at once`() {
        rolledBack { connection ->
            // `user_id IS NULL` is the load-bearing state of this whole table: it is
            // what makes a wedding created by one person complete rather than
            // half-built, and what gives `#9` a row to fill. So the three columns it
            // leaves empty have to be nullable — and `ddl-auto: validate` does not
            // check nullability at all (api/AGENTS.md, Schema ownership), which is
            // why it is checked here.
            val person = connection.insertUser()
            val ours = connection.insertWedding(person)
            val theirs = connection.insertWedding(person)

            connection.execute(waitingSeatInsert(ours, "BRIDE"))
            connection.execute(waitingSeatInsert(theirs, "BRIDE"))

            // Two empty seats, in two different weddings, both live. `ux_party_user`
            // is spelled `user_id is not null` for exactly this — several NULLs do
            // not collide in a partial index anyway, and the clause is what says the
            // empty seat was intended rather than tolerated.
            assertThat(
                connection.selectLong("select count(*) from wedding_party where user_id is null and name is null and joined_at is null"),
            ).isEqualTo(2)
        }
    }

    @Test
    fun `one 신랑 seat and one 신부 seat per wedding, and a released one frees its side`() {
        rolledBack { connection ->
            val person = connection.insertUser()
            val wedding = connection.insertWedding(person)
            connection.execute(seatInsert(wedding, "GROOM", person))

            // ux_party_wedding_side. Without it "every wedding has exactly two seats"
            // has no upper half, and a `#9` bug could seat three people.
            val third = connection.rejects(waitingSeatInsert(wedding, "GROOM"))
            assertThat(third.sqlState).isEqualTo(UNIQUE_VIOLATION)
            assertThat((third as PSQLException).serverErrorMessage?.constraint).isEqualTo("ux_party_wedding_side")

            // PARTIAL, for the reason every unique index in this schema is: a
            // soft-deleted row that kept its side would make the seat impossible to
            // re-create (notes/2026-08-10-decision-soft-delete.md).
            connection.execute("update wedding_party set deleted_at = now() where wedding_id = $wedding")
            connection.execute(waitingSeatInsert(wedding, "GROOM"))

            assertThat(
                connection.selectLong("select count(*) from wedding_party where wedding_id = $wedding and deleted_at is null"),
            ).isOne()
        }
    }

    @Test
    fun `user_id is served by exactly one index, and it is the unique partial one`() {
        rolledBack { connection ->
            // Asserted as "exactly one" rather than "the old name is gone", so a
            // second index over the same column also has to be argued for: it is the
            // hot path of the whole product, and a duplicate is a write on every seat
            // row buying nothing.
            assertThat(connection.indexesOn("wedding_party", "user_id")).hasSize(1)

            // Read back rather than inferred from behaviour, for the property
            // behaviour cannot distinguish: an index that is unique over the right
            // column by accident of some other definition. This is the one line that
            // says the FILE says `unique`.
            assertThat(connection.selectText("select pg_get_indexdef('${SoleSeatCollision.SOLE_SEAT_INDEX}'::regclass)"))
                .contains("CREATE UNIQUE INDEX")
                .contains("(user_id)")
                .contains("deleted_at IS NULL")
                .contains("user_id IS NOT NULL")

            assertThat(connection.selectText("select pg_get_indexdef('ux_party_wedding_side'::regclass)"))
                .contains("CREATE UNIQUE INDEX")
                .contains("(wedding_id, side)")
                .contains("deleted_at IS NULL")
        }
    }

    /** A seat with a side and nothing else — the shape `POST /weddings` gives the partner. */
    private fun waitingSeatInsert(
        weddingId: Long,
        side: String,
    ) = "insert into wedding_party (wedding_id, side, created_at, updated_at) values ($weddingId, '$side', now(), now())"

    private fun seatInsert(
        weddingId: Long,
        side: String,
        userId: Long,
    ) = "insert into wedding_party (wedding_id, side, name, user_id, joined_at, created_at, updated_at) " +
        "values ($weddingId, '$side', '테스터', $userId, now(), now(), now())"

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
