package com.donghaeng.auth

import org.springframework.security.oauth2.core.oidc.user.OidcUser

/**
 * What one Google login tells us about a person, reduced to the three things
 * `app_user` and `oauth_identity` can hold.
 *
 * The interesting field is [mergeKey], and everything it is allowed to be is
 * decided in [Companion.mergeKey]. `null` is the ordinary, safe answer; a non-null
 * value is a claim that Google checked mailbox control, and acting on that claim
 * seats a returning person on an existing account.
 */
internal data class GoogleProfile(
    val subject: String,
    val name: String?,
    val mergeKey: String?,
) {
    companion object {
        /**
         * Written to `oauth_identity.provider` and, when [mergeKey] survives, to
         * `app_user.email_verified_by` — where `ck_app_user_email_verifier_known`
         * admits only `GOOGLE` and `KAKAO`.
         */
        const val PROVIDER = "GOOGLE"

        fun of(user: OidcUser): GoogleProfile =
            GoogleProfile(
                subject = user.subject,
                name = user.fullName,
                mergeKey = mergeKey(user.email, user.emailVerified == true),
            )

        /**
         * The one place an address may become a merge key, and the four ways it
         * may not.
         *
         * Merging on an address the provider has not vouched for is a full ledger
         * takeover with no token, no expiry and no invite
         * (notes/2026-08-11-decision-baseline-schema-calls.md §A) — so [verified]
         * is checked first and nothing else can rescue a false.
         *
         * The three shape rules mirror `ck_app_user_email_shape` and
         * `ux_app_user_email` exactly, and each has its own failure:
         *
         * - **ASCII-only lowercase**, because the unique index is built on
         *   `lower(email collate "C")`. Kotlin's `String.lowercase()` is full
         *   Unicode case mapping — `'İ'` comes back as two code points — so using
         *   it here would write a value the index folds differently from the way
         *   this code folded it, and the lookup would miss the row it just wrote.
         * - **No whitespace, after trimming**, because to the schema
         *   `' kim@gmail.com'` and `'kim@gmail.com'` are two people.
         * - **One `@` with something on each side**, because `""` is a legal
         *   varchar and a provider that returns it for an absent optional field
         *   would otherwise write an empty VERIFIED address — one `app_user`
         *   shared by every stranger whose provider did the same.
         *
         * Failing any of them yields `null`, and the account simply stands alone.
         * v1 has no account-linking flow, so standing alone is the designed
         * outcome rather than a degraded one.
         */
        fun mergeKey(
            email: String?,
            verified: Boolean,
        ): String? {
            if (!verified) return null
            val candidate = email?.trim()?.let(::asciiLowercase) ?: return null
            if (candidate.any(Char::isWhitespace)) return null
            val at = candidate.indexOf('@')
            return candidate.takeIf { at > 0 && at < candidate.length - 1 }
        }

        /**
         * `lower(... collate "C")` in Kotlin: the ASCII range and nothing else.
         * Every other character is left exactly as it arrived, which is what makes
         * this function agree with the index for every input rather than for the
         * inputs anyone thought to try.
         */
        fun asciiLowercase(value: String): String = value.map { if (it in 'A'..'Z') it + LOWERCASE_OFFSET else it }.joinToString("")

        private const val LOWERCASE_OFFSET = 'a' - 'A'
    }
}
