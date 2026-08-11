package com.donghaeng.guestimport

import com.donghaeng.BaselineSchemaFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * What `ux_guest_import_wedding_file` enforces in `V1__baseline_schema.sql`.
 *
 * The index has three halves in its predicate — the wedding, `SUCCEEDED`, and
 * `superseded_at is null` — and each was chosen against a specific failure
 * (notes/2026-08-11-decision-baseline-schema-calls.md §B). A predicate typed
 * with two of the three still creates an index and still refuses SOME
 * duplicates, so the wrong one is not loud.
 *
 * What this file does NOT test is the writer contract the index depends on:
 * supersede first, abort on `0 rows superseded`, guest writes and the flip in
 * one transaction. That contract is stated on the index in the migration and is
 * #20/#21's to implement and to test — a schema test cannot observe an ordering
 * no service performs yet.
 *
 * See BaselineSchemaFixture for what "enforces" reaches: the file, not prod.
 */
internal class GuestImportSchemaTest : BaselineSchemaFixture() {
    @Test
    fun `only one live SUCCEEDED import may hold a file hash, and only within its own wedding`() {
        rolledBack { connection ->
            val user = connection.insertUser()
            val wedding = connection.insertWedding(user)
            val other = connection.insertWedding(user)

            connection.execute(importInsert(wedding, HASH, "SUCCEEDED", user))

            // The three halves of the predicate, each shown to be load-bearing by
            // a row the index must NOT refuse.
            //
            // RECEIVED: two concurrent uploads of one file both get their row, so
            // guest_change's FK has a target while the parse runs. This is also
            // exactly what the index cannot catch — the violation only fires at
            // the flip, after a second run has already parsed and written.
            connection.execute(importInsert(wedding, HASH, "RECEIVED", user))
            // FAILED: an import that died mid-parse must not lock the file
            // forever.
            connection.execute(importInsert(wedding, HASH, "FAILED", user))
            // Another wedding: global uniqueness would let a stranger's import
            // block ours, and the refusal itself would be an existence oracle.
            connection.execute(importInsert(other, HASH, "SUCCEEDED", user))

            val duplicate = connection.rejects(importInsert(wedding, HASH, "SUCCEEDED", user))

            assertThat(duplicate.sqlState).isEqualTo(UNIQUE_VIOLATION)
            assertThat(duplicate.message).contains("ux_guest_import_wedding_file")
        }
    }

    @Test
    fun `superseding the live import frees the hash for exactly one successor`() {
        rolledBack { connection ->
            val user = connection.insertUser()
            val wedding = connection.insertWedding(user)
            val first = connection.insertReturningId(importInsert(wedding, HASH, "SUCCEEDED", user))

            // The forced re-import: stamp the live row, then take the slot. Both
            // statements are one transaction in the real writer, and the stamp
            // goes FIRST — its row lock is the only thing that serialises two
            // concurrent forces (see the migration comment on the index).
            connection.execute("update guest_import set superseded_at = now() where id = $first")
            val second = connection.insertReturningId(importInsert(wedding, HASH, "SUCCEEDED", user))

            // "Exactly one" is the half that would be lost by dropping
            // `superseded_at is null` from the predicate rather than by adding
            // it: the slot must be freed once, not opened permanently.
            val third = connection.rejects(importInsert(wedding, HASH, "SUCCEEDED", user))

            assertThat(third.sqlState).isEqualTo(UNIQUE_VIOLATION)
            assertThat(third.message).contains("ux_guest_import_wedding_file")
            assertThat(
                connection.selectLong(
                    "select id from guest_import " +
                        "where wedding_id = $wedding and file_hash = '$HASH' " +
                        "and status = 'SUCCEEDED' and superseded_at is null",
                ),
            ).isEqualTo(second)
        }
    }

    private fun importInsert(
        weddingId: Long,
        fileHash: String,
        status: String,
        uploadedBy: Long,
    ) = "insert into guest_import (wedding_id, file_hash, status, uploaded_by, created_at) " +
        "values ($weddingId, '$fileHash', '$status', $uploadedBy, now())"

    private companion object {
        const val UNIQUE_VIOLATION = "23505"

        // Shape-accurate: the column is varchar(64) and holds hex SHA-256.
        const val HASH = "0000000000000000000000000000000000000000000000000000000000000001"
    }
}
