package com.donghaeng.auth.login

import com.donghaeng.auth.session.SessionService
import com.donghaeng.auth.session.SessionToken
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Turns a completed round trip at any provider into an `app_user` and a session.
 *
 * The order of the two lookups in [findOrCreateUserId] is the whole design, and it
 * is the order `#82` exists about: the provider subject first, the verified email
 * only if that misses, an account only if both do.
 *
 * Nothing here names a provider. Which one spoke is carried by
 * [ProviderProfile.provider], so `#89` adds mappers and changes no logic in THIS
 * file — which is a claim about this file only. `#89` still restructures
 * [OAuthLoginSuccessHandler], because Naver is plain OAuth 2.0 and produces no
 * `OidcUser` to map from (notes/2026-08-12-decision-login-slice-by-provider.md).
 */
@Service
internal class LoginService(
    private val users: AppUserRepository,
    private val identities: OauthIdentityRepository,
    private val sessions: SessionService,
) {
    @Transactional
    fun login(
        profile: ProviderProfile,
        presented: SessionToken?,
        now: Instant = Instant.now(),
    ): SessionToken {
        val userId = findOrCreateUserId(profile, now)
        return sessions.issue(userId, presented, now)
    }

    private fun findOrCreateUserId(
        profile: ProviderProfile,
        now: Instant,
    ): Long {
        identities.findByProviderAndProviderUserId(profile.provider, profile.subject)?.let { identity ->
            return identity.userId
        }

        // No identity row, so this is a first login for this provider account. It
        // is not necessarily a first login for this PERSON: the verified address is
        // the merge key, and missing the existing row here is what #82 describes —
        // the create branch runs, `ux_app_user_email` refuses it, and a silent
        // account split becomes a 500 on login.
        val existing = profile.mergeKey?.let(users::findByMergeKey)
        val user = existing ?: users.save(newUser(profile, now))

        identities.save(
            OauthIdentity(
                userId = user.id,
                provider = profile.provider,
                providerUserId = profile.subject,
                createdAt = now,
            ),
        )
        return user.id
    }

    /**
     * The address is written only when the provider's mapper let it survive its
     * checks, and `email_verified_by` is written in the same breath —
     * `ck_app_user_email_verified_by` makes the pairing total in both directions,
     * so half of it is not a legal row, and
     * `ck_app_user_email_verifier_known` will refuse a provider that cannot vouch.
     */
    private fun newUser(
        profile: ProviderProfile,
        now: Instant,
    ): AppUser =
        AppUser(
            email = profile.mergeKey,
            emailVerifiedBy = profile.mergeKey?.let { profile.provider },
            name = profile.name,
            createdAt = now,
            updatedAt = now,
        )
}
