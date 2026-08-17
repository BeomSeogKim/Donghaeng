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
 *
 * The profile refresh it makes on the merge path is **not** one of those two writes:
 * [ProfileRefreshService] holds its own boundary, so a name Postgres refuses cannot
 * roll this registration back.
 */
@Service
internal class AccountRegistrationService(
    private val users: AppUserRepository,
    private val identities: OauthIdentityRepository,
    private val profiles: ProfileRefreshService,
) {
    /**
     * The merge lookup is here rather than in the caller so that the retry
     * [LoginService] runs re-reads it inside a NEW transaction — on the second pass
     * the winner's `app_user` has committed, so what was a unique violation the
     * first time is an ordinary merge the second (#82).
     *
     * Which is why the merge half of `#94`'s refresh is here too: this lookup is the
     * second of the two doors into an existing person, and a person recognised by
     * their verified address is returning exactly as much as one recognised by their
     * subject id.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun register(
        profile: ProviderProfile,
        now: Instant,
    ): Long {
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

        // Only for the person who was already here (#94). The create branch above
        // has just written the same name from the same profile, so refreshing it
        // would be a statement whose answer is known — and one that could not see
        // its own uncommitted row anyway.
        //
        // AFTER the identity insert, deliberately: `id` is IDENTITY-generated, so
        // `save` above has already round-tripped and a losing attempt has already
        // thrown. Refreshing first would rename a person on an attempt that then
        // rolls back and hands the login to a different account entirely — which is
        // the second pass of the double-collision race.
        //
        // AFTER THIS LINE, `existing.name` IS STALE. The refresh commits in a
        // transaction of its own and therefore in its own EntityManager, so THIS
        // transaction's context still holds the name the row had before it — and
        // this transaction commits after this point, so a later
        // `existing.updatedAt = now` here would flush the whole stale entity and
        // silently undo the refresh. `clearAutomatically` on the query would not
        // help — it clears the persistence context the statement ran in, which is
        // not this one. Re-read the row if you ever need it after this point.
        existing?.let { profiles.refresh(it.id, profile, now) }

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
