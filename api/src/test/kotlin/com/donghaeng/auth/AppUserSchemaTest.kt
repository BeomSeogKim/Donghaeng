package com.donghaeng.auth

import com.donghaeng.BaselineSchemaFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * What `app_user` enforces about the ACCOUNT MERGE KEY in
 * `V1__baseline_schema.sql`.
 *
 * Every assertion here defends one sentence: an email in that column is a claim
 * that a provider checked mailbox control, and `lower(email collate "C")` is the
 * key a second login is merged onto
 * (notes/2026-08-11-decision-baseline-schema-calls.md §A). A wrong key here is
 * not a wrong number — it seats a stranger on someone else's `app_user`, with
 * their memberships and therefore the whole ledger, with no token and no expiry.
 *
 * See BaselineSchemaFixture for what "enforces" reaches: the file, not prod.
 */
internal class AppUserSchemaTest : BaselineSchemaFixture() {
    @Test
    fun `NAVER cannot be recorded as the verifier, because Naver verifies nothing`() {
        rolledBack { connection ->
            // Naver's profile API returns an email with no verification flag at
            // all, and the address is user-editable — so this row would be a
            // false statement, and acting on it is the takeover §A exists to
            // close. The biconditional CHECK makes writing it the path of least
            // resistance (a verifier is demanded whenever an email is present),
            // which is exactly why the value set needs its own constraint.
            val naver = connection.rejects(userInsert("kim@gmail.com", "NAVER"))

            assertThat(naver.sqlState).isEqualTo(CHECK_VIOLATION)
            assertThat(naver.message).contains("ck_app_user_email_verifier_known")

            // The two that can vouch: Google's `email_verified` and Kakao's
            // `is_email_verified`.
            connection.execute(userInsert("kim@gmail.com", "GOOGLE"))
            connection.execute(userInsert("lee@gmail.com", "KAKAO"))
        }
    }

    @Test
    fun `an empty or whitespace-bearing email is rejected, so no two people share one merge key`() {
        rolledBack { connection ->
            // A provider that returns "" for an absent optional field instead of
            // omitting it. Stored, '' is a legal VERIFIED address that every
            // later empty-email login merges onto — not a split but one app_user
            // shared by strangers, first registrant holding the row.
            val empty = connection.rejects(userInsert("", "GOOGLE"))
            // The same argument one step out: to this schema ' kim@gmail.com'
            // and 'kim@gmail.com' are two people.
            val untrimmed = connection.rejects(userInsert(" kim@gmail.com", "GOOGLE"))

            assertThat(empty.sqlState).isEqualTo(CHECK_VIOLATION)
            assertThat(untrimmed.sqlState).isEqualTo(CHECK_VIOLATION)
            assertThat(empty.message).contains("ck_app_user_email_shape")
            assertThat(untrimmed.message).contains("ck_app_user_email_shape")

            // Not over-broad: the constraint validates a merge key, not an
            // address. An ordinary one passes, and so does no email at all.
            connection.execute(userInsert("kim@gmail.com", "GOOGLE"))
            connection.execute(userInsert(null, null))
        }
    }

    @Test
    fun `the merge key folds ASCII case only, so two distinct addresses cannot collapse into one`() {
        rolledBack { connection ->
            // What the key must still DO: one person arriving via a second
            // provider with different casing is one account, not two.
            connection.execute(userInsert("Kim@Gmail.com", "GOOGLE"))
            val sameAddress = connection.rejects(userInsert("kim@gmail.com", "KAKAO"))

            assertThat(sameAddress.sqlState).isEqualTo(UNIQUE_VIOLATION)
            assertThat(sameAddress.message).contains("ux_app_user_email")

            // What it must NOT do, and this is the assertion that survives a
            // rewrite of the index. Under this database's own ctype `lower()` is
            // not injective — `lower('KİM@X.COM') = lower('KIM@X.COM')` is TRUE
            // while the two addresses are not equal, so they share one merge key
            // and the second person is seated on the first one's account.
            // `collate "C"` is what keeps the fold ASCII-only.
            assertThat(
                connection.selectBoolean(
                    """select lower('KİM@X.COM' collate "C") = lower('KIM@X.COM' collate "C")""",
                ),
            ).isFalse()

            // And through the index, not only the function: built without the
            // collation, the second insert here is a unique violation.
            connection.execute(userInsert("KİM@X.COM", "GOOGLE"))
            connection.execute(userInsert("KIM@X.COM", "GOOGLE"))

            // The collation is the one property of this file that a Testcontainers
            // run cannot infer from the database it happens to be given: the
            // container is en_US.utf8/libc and a managed Postgres 16 may be ICU.
            // Reading the definition back is what pins it to the FILE.
            assertThat(connection.selectText("select pg_get_indexdef('ux_app_user_email'::regclass)"))
                .contains("COLLATE \"C\"")
        }
    }

    private fun userInsert(
        email: String?,
        verifiedBy: String?,
    ) = "insert into app_user (email, email_verified_by, name, created_at, updated_at) " +
        "values (${email.quoted()}, ${verifiedBy.quoted()}, '테스터', now(), now())"

    private fun String?.quoted(): String = if (this == null) "null" else "'$this'"

    private companion object {
        const val CHECK_VIOLATION = "23514"
        const val UNIQUE_VIOLATION = "23505"
    }
}
