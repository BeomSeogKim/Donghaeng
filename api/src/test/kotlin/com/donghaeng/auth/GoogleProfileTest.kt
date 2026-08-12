package com.donghaeng.auth

import com.donghaeng.BaselineSchemaFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The merge key, on both sides of the seam.
 *
 * `ux_app_user_email` is built on `lower(email collate "C")`, and `AppUserSchemaTest`
 * already holds what the index REFUSES. What nothing held until now is that the
 * application folds case the same way the index does — and it is not the obvious
 * function: Kotlin's `String.lowercase()` is full Unicode case mapping, so the two
 * disagree on inputs no test would think to try. Disagreeing means the lookup
 * misses the row the index forbids it to duplicate, which is `#82`.
 *
 * So the fold is compared against the DATABASE's own answer rather than against a
 * table of expectations someone wrote by hand.
 */
internal class GoogleProfileTest : BaselineSchemaFixture() {
    @Test
    fun `the application folds case exactly as lower with the C collation does`() {
        rolledBack { connection ->
            // The third one is the case that matters, and the reason
            // String.lowercase() cannot be used: it maps 'İ' to two code points
            // ("i" plus a combining dot), while `lower(... collate "C")` leaves
            // every non-ASCII byte alone. One of those two values is in the index
            // and the other is what the lookup would ask for.
            listOf("Kim@Gmail.com", "KIM@X.COM", "KİM@X.COM", "kim@gmail.com", "GIL동@naver.com")
                .forEach { address ->
                    assertThat(GoogleProfile.asciiLowercase(address))
                        .describedAs("the fold of %s", address)
                        .isEqualTo(connection.selectText("""select lower('$address' collate "C")"""))
                }

            // Stated separately so the reason above is not merely asserted by
            // implication: the obvious function is wrong here.
            assertThat("KİM@X.COM".lowercase()).isNotEqualTo(GoogleProfile.asciiLowercase("KİM@X.COM"))
        }
    }

    @Test
    fun `only a verified, well-shaped address becomes a merge key`() {
        // Verified is checked first and nothing else can rescue a false: merging
        // on an address the provider did not vouch for is a full ledger takeover
        // with no token and no expiry.
        assertThat(GoogleProfile.mergeKey("kim@gmail.com", verified = false)).isNull()
        assertThat(GoogleProfile.mergeKey(null, verified = true)).isNull()

        // The three shape rules, each mirroring a constraint that would otherwise
        // turn a login into a 500 — or worse, into one app_user shared by
        // strangers.
        assertThat(GoogleProfile.mergeKey("", verified = true)).isNull()
        assertThat(GoogleProfile.mergeKey("   ", verified = true)).isNull()
        assertThat(GoogleProfile.mergeKey("kim gmail.com", verified = true)).isNull()
        assertThat(GoogleProfile.mergeKey("@gmail.com", verified = true)).isNull()
        assertThat(GoogleProfile.mergeKey("kim@", verified = true)).isNull()

        // What it must still do: trim, fold, and let an ordinary address through.
        assertThat(GoogleProfile.mergeKey(" Kim@Gmail.com ", verified = true)).isEqualTo("kim@gmail.com")
    }
}
