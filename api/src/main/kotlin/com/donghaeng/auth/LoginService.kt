package com.donghaeng.auth

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Turns a completed Google round trip into an `app_user` and a session.
 *
 * The order of the two lookups in [login] is the whole design, and it is the order
 * `#82` exists about: the provider subject first, the verified email only if that
 * misses, an account only if both do.
 */
@Service
internal class LoginService(
    private val users: AppUserRepository,
    private val identities: OauthIdentityRepository,
    private val sessions: SessionService,
) {
    @Transactional
    fun login(
        profile: GoogleProfile,
        presented: SessionToken?,
        now: Instant = Instant.now(),
    ): SessionToken {
        val userId = findOrCreateUserId(profile, now)
        return sessions.issue(userId, presented, now)
    }

    private fun findOrCreateUserId(
        profile: GoogleProfile,
        now: Instant,
    ): Long {
        identities.findByProviderAndProviderUserId(GoogleProfile.PROVIDER, profile.subject)?.let { identity ->
            return identity.userId
        }

        // No identity row, so this is a first login for this Google account. It is
        // not necessarily a first login for this PERSON: the verified address is
        // the merge key, and missing the existing row here is what #82 describes —
        // the create branch runs, `ux_app_user_email` refuses it, and a silent
        // account split becomes a 500 on login.
        val existing = profile.mergeKey?.let(users::findByMergeKey)
        val user = existing ?: users.save(newUser(profile, now))

        identities.save(
            OauthIdentity(
                userId = user.id,
                provider = GoogleProfile.PROVIDER,
                providerUserId = profile.subject,
                createdAt = now,
            ),
        )
        return user.id
    }

    /**
     * The address is written only when [GoogleProfile.mergeKey] survived its
     * checks, and `email_verified_by` is written in the same breath —
     * `ck_app_user_email_verified_by` makes the pairing total in both directions,
     * so half of it is not a legal row.
     */
    private fun newUser(
        profile: GoogleProfile,
        now: Instant,
    ): AppUser =
        AppUser(
            email = profile.mergeKey,
            emailVerifiedBy = profile.mergeKey?.let { GoogleProfile.PROVIDER },
            name = profile.name,
            createdAt = now,
            updatedAt = now,
        )
}
