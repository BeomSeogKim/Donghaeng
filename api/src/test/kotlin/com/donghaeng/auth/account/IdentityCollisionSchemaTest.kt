package com.donghaeng.auth.account

import com.donghaeng.BaselineSchemaFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.Connection

/**
 * Binds [IdentityCollision]'s allowlist to the schema, because nothing else does.
 *
 * The allowlist is two index names in Kotlin source. A rename in
 * `V1__baseline_schema.sql` — or, far more likely, a hand-typed database where the
 * name was typed differently, which is how every real DDL statement gets applied
 * here (api/AGENTS.md, Schema ownership) — makes `alreadyRegistered` answer `false`
 * forever. Login then goes back to the masked 500 `#93` removed, with the whole
 * suite green, because every other test builds its schema from the migration file.
 *
 * This pins the FILE, exactly as far as [BaselineSchemaFixture] pins anything: it
 * cannot see the founder's typing. What it buys is that the manuscript and the code
 * cannot drift apart in the repository, which is the half that is checkable.
 */
internal class IdentityCollisionSchemaTest : BaselineSchemaFixture() {
    @Test
    fun `the constraints login recovers from are exactly the unique indexes on the identity tables`() {
        rolledBack { connection ->
            // EQUALITY, not containment. A third unique index on `app_user` — an
            // account-linking table's key, a phone number, whatever #94 brings —
            // fails here until someone decides whether losing to it means "already
            // exists". Containment would let it default into the branch that
            // rethrows, which is the branch that shows the user a 500.
            assertThat(connection.uniqueIndexesOn("app_user", "oauth_identity"))
                .isEqualTo(IdentityCollision.ALREADY_REGISTERED)
        }
    }

    /**
     * Primary keys are excluded because they are over generated identity columns:
     * nothing races for one, and a violation there is a corrupted sequence rather
     * than a second login.
     */
    private fun Connection.uniqueIndexesOn(vararg tables: String): Set<String> {
        val names = tables.joinToString(", ") { "'$it'" }
        return createStatement().use { statement ->
            statement
                .executeQuery(
                    """
                    select index_class.relname
                    from pg_index i
                    join pg_class index_class on index_class.oid = i.indexrelid
                    join pg_class table_class on table_class.oid = i.indrelid
                    join pg_namespace schema on schema.oid = table_class.relnamespace
                    where schema.nspname = 'public'
                      and table_class.relname in ($names)
                      and i.indisunique
                      and not i.indisprimary
                    """.trimIndent(),
                ).use { rows ->
                    generateSequence { if (rows.next()) rows.getString(1) else null }.toSet()
                }
        }
    }
}
