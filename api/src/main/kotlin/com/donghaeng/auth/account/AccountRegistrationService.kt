package com.donghaeng.auth.account

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * One attempt to give a first-time arrival an account, in a transaction of its own.
 *
 * It is a separate bean for one reason, and it is the whole of `#93`'s mechanism: a
 * constraint violation on flush marks the transaction ROLLBACK-ONLY, so the caller
 * cannot catch it and re-read on the same transaction — the very next query would
 * fail too. The attempt therefore has to be able to fail and be rolled back
 * *independently* of the login that made it, which means a transaction boundary
 * the caller is outside of, which in Spring means a call through a proxy. Keeping
 * this in [LoginService] and annotating a private method would compile, self-invoke,
 * and share the poisoned transaction.
 *
 * `REQUIRES_NEW` rather than `REQUIRED` for the same reason one step out: if a
 * caller ever runs inside a transaction of its own, `REQUIRED` would enlist in it
 * and the rollback would take the caller's work with it.
 *
 * **The two writes are ONE transaction, deliberately.** A person whose provider gave
 * us no verified address has nothing but the subject to collide on, so their losing
 * attempt is refused at `oauth_identity` — after `app_user` has already been
 * inserted. Split into two transactions, that leaves an orphan account per race:
 * no identity, no session, invisible, and it is a *person* row.
 */
@Service
internal class AccountRegistrationService(
    private val users: AppUserRepository,
    private val identities: OauthIdentityRepository,
) {
    /**
     * The merge lookup is here rather than in the caller so that the retry
     * [LoginService] runs re-reads it inside a NEW transaction — on the second pass
     * the winner's `app_user` has committed, so what was a unique violation the
     * first time is an ordinary merge the second (#82).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun register(
        profile: ProviderProfile,
        now: Instant,
    ): Long {
        val user = profile.mergeKey?.let(users::findByMergeKey) ?: users.save(newUser(profile, now))
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
