package com.donghaeng.auth

import org.springframework.security.oauth2.core.oidc.user.OidcUser

/**
 * Google's claims, mapped to a [ProviderProfile]. `#89` adds a sibling for Kakao
 * — which reads `is_email_verified` as a field separate from the address — and one
 * for Naver, which can never produce a merge key at all.
 */
internal object GoogleProfile {
    /**
     * Written to `oauth_identity.provider` and, when [mergeKey] survives, to
     * `app_user.email_verified_by` — where `ck_app_user_email_verifier_known`
     * admits only `GOOGLE` and `KAKAO`.
     */
    const val PROVIDER = "GOOGLE"

    /**
     * `app_user.name` is `varchar(100)`, and nothing bounds what a provider puts
     * in the claim — Google documents no limit on `name`. An over-long value would
     * reach Postgres as `value too long for type character varying(100)`, inside
     * the login success handler, where it becomes a masked 500 with no `code` the
     * frontend can act on and no path forward for that person: they would fail
     * every login attempt, forever, for having a long display name.
     *
     * Truncated rather than validated because it is display text and never a key.
     * Nothing joins on it, nothing merges on it, and a name cut at 100 characters
     * is a cosmetic flaw where a refused login is a locked-out couple.
     */
    private const val NAME_LIMIT = 100

    fun of(user: OidcUser): ProviderProfile =
        ProviderProfile(
            provider = PROVIDER,
            subject = user.subject,
            name = user.fullName?.take(NAME_LIMIT),
            mergeKey = mergeKey(user.email, user.emailVerified == true),
        )

    /**
     * The one place a Google address may become a merge key, and the four ways it
     * may not.
     *
     * Merging on an address the provider has not vouched for is a full ledger
     * takeover with no token, no expiry and no invite
     * (notes/2026-08-11-decision-baseline-schema-calls.md §A) — so [verified] is
     * checked first and nothing else can rescue a false.
     *
     * The three shape rules mirror `ck_app_user_email_shape` and
     * `ux_app_user_email` exactly, and each has its own failure:
     *
     * - **ASCII-only lowercase**, because the unique index is built on
     *   `lower(email collate "C")`. Kotlin's `String.lowercase()` is full Unicode
     *   case mapping — `'İ'` comes back as two code points — so using it here would
     *   write a value the index folds differently from the way this code folded it,
     *   and the lookup would miss the row it just wrote.
     * - **No whitespace, after trimming**, because to the schema
     *   `' kim@gmail.com'` and `'kim@gmail.com'` are two people.
     * - **One `@` with something on each side**, because `""` is a legal varchar
     *   and a provider that returns it for an absent optional field would otherwise
     *   write an empty VERIFIED address — one `app_user` shared by every stranger
     *   whose provider did the same.
     *
     * Failing any of them yields `null`, and the account simply stands alone. v1
     * has no account-linking flow, so standing alone is the designed outcome rather
     * than a degraded one.
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
     * `lower(... collate "C")` in Kotlin: the ASCII range and nothing else. Every
     * other character is left exactly as it arrived, which is what makes this
     * function agree with the index for every input rather than for the inputs
     * anyone thought to try.
     */
    fun asciiLowercase(value: String): String = value.map { if (it in 'A'..'Z') it + LOWERCASE_OFFSET else it }.joinToString("")

    private const val LOWERCASE_OFFSET = 'a' - 'A'
}
