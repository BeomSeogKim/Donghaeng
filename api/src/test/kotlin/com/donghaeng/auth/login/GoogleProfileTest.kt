package com.donghaeng.auth.login

import com.donghaeng.BaselineSchemaFixture
import com.donghaeng.auth.login.GoogleProfile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames
import org.springframework.security.oauth2.core.oidc.OidcIdToken
import org.springframework.security.oauth2.core.oidc.OidcUserInfo
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import java.time.Instant

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

        // Longer than the column. NOT truncated like a name is: an address is a
        // KEY, so cutting it would merge two people sharing a 255-character
        // prefix — the takeover this file exists to prevent, reached by tidiness.
        // No key means the account stands alone, which is a designed outcome.
        val tooLong = "a".repeat(250) + "@gmail.com"
        assertThat(tooLong).hasSizeGreaterThan(255)
        assertThat(GoogleProfile.mergeKey(tooLong, verified = true)).isNull()
        assertThat(GoogleProfile.mergeKey("a".repeat(245) + "@gmail.com", verified = true)).isNotNull()

        // What it must still do: trim, fold, and let an ordinary address through.
        assertThat(GoogleProfile.mergeKey(" Kim@Gmail.com ", verified = true)).isEqualTo("kim@gmail.com")
    }

    @Test
    fun `a display name longer than the column is truncated rather than refused`() {
        // Google documents no bound on the `name` claim and `app_user.name` is
        // varchar(100). Untruncated, a 101-character name reaches Postgres as
        // `value too long`, inside the login success handler, as a masked 500 —
        // and that person can never log in, forever, for having a long name.
        // Display text is truncatable; a login is not.
        val long = "가".repeat(300)

        assertThat(GoogleProfile.of(oidcUser(name = long)).name).hasSize(100)

        // BY CODE POINT, and the case has to STRADDLE the boundary to prove it.
        // `String.take` counts UTF-16 units, so a name whose 100th code point is
        // an emoji gets cut between the surrogates and ends with a lone high
        // surrogate — not text, and encoded by the driver as a replacement byte.
        // An emoji sitting past the limit would be discarded by either version and
        // proves nothing, which is what the first version of this test did.
        val bride = "\uD83D\uDC70" // one code point, two UTF-16 units
        val straddling = "가".repeat(99) + bride + "나".repeat(10)

        val cut = GoogleProfile.of(oidcUser(name = straddling)).name!!

        assertThat(cut.codePointCount(0, cut.length)).isEqualTo(100)
        assertThat(cut).endsWith(bride)
        assertThat(cut.last().isHighSurrogate()).isFalse()

        // And a name exactly at the limit is left alone, emoji included.
        val exact = "가".repeat(99) + bride
        assertThat(GoogleProfile.of(oidcUser(name = exact)).name).isEqualTo(exact)
    }

    @Test
    fun `the profile carries the provider that spoke, not a constant`() {
        assertThat(GoogleProfile.of(oidcUser(name = "김테스터")).provider).isEqualTo("GOOGLE")
    }

    private fun oidcUser(name: String?): OidcUser {
        val claims =
            buildMap<String, Any> {
                put(IdTokenClaimNames.SUB, "google-subject")
                name?.let { put("name", it) }
            }
        val idToken = OidcIdToken(TOKEN_VALUE, Instant.now(), Instant.now().plusSeconds(60), claims)
        return DefaultOidcUser(emptyList(), idToken, OidcUserInfo(claims))
    }

    private companion object {
        const val TOKEN_VALUE = "stub-id-token"
    }
}
