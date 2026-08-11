package com.donghaeng.guest

import com.donghaeng.BaselineSchemaFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.Connection

/**
 * What `guest_meal_count` enforces in `V1__baseline_schema.sql`, and it is the
 * one table in that file where the schema was chosen INSTEAD OF service-layer
 * validation (notes/2026-08-11-decision-baseline-schema-calls.md §C). The
 * composite FKs are therefore the only thing standing between a body-supplied
 * `meal_type_id` (#14) and a cross-tenant write, so nothing else will notice if
 * one of them is skipped while the file is typed by hand.
 *
 * See BaselineSchemaFixture for what "enforces" reaches: the file, not prod.
 */
internal class GuestMealCountSchemaTest : BaselineSchemaFixture() {
    @Test
    fun `a meal type from another wedding is rejected exactly as a meal type that does not exist`() {
        rolledBack { connection ->
            val user = connection.insertUser()
            val ours = connection.insertWedding(user)
            val theirs = connection.insertWedding(user)
            val ourGuest = connection.insertGuest(ours, user, "김하객")
            val theirMealType = connection.insertMealType(theirs, "유아식")

            // The oracle this closes: `meal_type_id` arrives in the request body,
            // so it never passes CurrentWedding resolution. If the two outcomes
            // differed at all — one an FK violation, the other a clean insert, or
            // even two DIFFERENT violations — the API would answer differently for
            // a stranger's id than for a made-up one, and the difference is a
            // wedding-id oracle
            // (notes/2026-08-10-decision-cross-tenant-status-code.md).
            val crossWedding =
                connection.rejects(mealCountInsert(ours, ourGuest, theirMealType))
            val nonexistent =
                connection.rejects(mealCountInsert(ours, ourGuest, NONEXISTENT_ID))

            // Same SQLSTATE and the same constraint, which is what "identically"
            // has to mean here. The two DETAIL lines differ by the id that was
            // rejected, and that never reaches a response: both surface as the
            // same 404.
            assertThat(crossWedding.sqlState).isEqualTo(FOREIGN_KEY_VIOLATION)
            assertThat(nonexistent.sqlState).isEqualTo(FOREIGN_KEY_VIOLATION)
            assertThat(crossWedding.message).contains("fk_guest_meal_count_meal_type")
            assertThat(nonexistent.message).contains("fk_guest_meal_count_meal_type")
        }
    }

    @Test
    fun `a guest from another wedding is rejected exactly as a guest that does not exist`() {
        // The mirror of the case above. It is here because the risk being
        // defended against is a hand-typed manuscript losing ONE constraint, and
        // a test that only exercises fk_guest_meal_count_meal_type would stay
        // green with fk_guest_meal_count_guest missing.
        rolledBack { connection ->
            val user = connection.insertUser()
            val ours = connection.insertWedding(user)
            val theirs = connection.insertWedding(user)
            val theirGuest = connection.insertGuest(theirs, user, "남의하객")
            val ourMealType = connection.insertMealType(ours, "기본")

            val crossWedding =
                connection.rejects(mealCountInsert(ours, theirGuest, ourMealType))
            val nonexistent =
                connection.rejects(mealCountInsert(ours, NONEXISTENT_ID, ourMealType))

            assertThat(crossWedding.sqlState).isEqualTo(FOREIGN_KEY_VIOLATION)
            assertThat(nonexistent.sqlState).isEqualTo(FOREIGN_KEY_VIOLATION)
            assertThat(crossWedding.message).contains("fk_guest_meal_count_guest")
            assertThat(nonexistent.message).contains("fk_guest_meal_count_guest")
        }
    }

    @Test
    fun `a meal count aggregation excludes a soft-deleted guest only when it joins guest`() {
        rolledBack { connection ->
            val user = connection.insertUser()
            val wedding = connection.insertWedding(user)
            val mealType = connection.insertMealType(wedding, "기본")
            val living = connection.insertGuest(wedding, user, "산하객")
            val deleted = connection.insertGuest(wedding, user, "지운하객", deleted = true)
            connection.execute(mealCountInsert(wedding, living, mealType, expectedCount = 3))
            connection.execute(mealCountInsert(wedding, deleted, mealType, expectedCount = 2))

            // THE WRONG QUERY, asserted so the hazard is a fact and not a warning
            // in a comment. `guest_meal_count.wedding_id` is an FK component, and
            // the moment it is used as a predicate the soft-deleted guest's meals
            // are counted: @SQLRestriction does not reach a native query
            // (notes/2026-08-10-decision-soft-delete.md, consequence 1), so this
            // does not throw — it over-counts, and over-counting 보증인원 is
            // money. Two 유아식 that nobody will eat, paid for.
            assertThat(
                connection.selectLong(
                    "select sum(expected_count) from guest_meal_count where wedding_id = $wedding",
                ),
            ).isEqualTo(5)

            // The only shape #17 may use: reach the wedding THROUGH guest, and
            // filter deleted_at there.
            assertThat(
                connection.selectLong(
                    "select sum(gmc.expected_count) from guest_meal_count gmc " +
                        "join guest g on g.id = gmc.guest_id " +
                        "where g.wedding_id = $wedding and g.deleted_at is null",
                ),
            ).isEqualTo(3)
        }
    }

    private fun mealCountInsert(
        weddingId: Long,
        guestId: Long,
        mealTypeId: Long,
        expectedCount: Int = 1,
    ) = "insert into guest_meal_count (wedding_id, guest_id, meal_type_id, expected_count) " +
        "values ($weddingId, $guestId, $mealTypeId, $expectedCount)"

    private fun Connection.insertGuest(
        weddingId: Long,
        createdBy: Long,
        name: String,
        deleted: Boolean = false,
    ): Long =
        insertReturningId(
            "insert into guest (wedding_id, name, side, group_category, expected_attending, " +
                "expected_party_size, created_by, created_at, updated_by, updated_at, deleted_at) " +
                "values ($weddingId, '$name', 'GROOM', 'FRIEND', true, 1, " +
                "$createdBy, now(), $createdBy, now(), ${if (deleted) "now()" else "null"})",
        )

    private fun Connection.insertMealType(
        weddingId: Long,
        name: String,
    ): Long =
        insertReturningId(
            "insert into meal_type (wedding_id, name, created_at) values ($weddingId, '$name', now())",
        )

    private companion object {
        const val FOREIGN_KEY_VIOLATION = "23503"

        // An id no fixture in this class mints, so the FK has nothing to resolve.
        const val NONEXISTENT_ID = 9_999_999L
    }
}
