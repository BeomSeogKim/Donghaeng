package com.donghaeng.auth.account

import com.donghaeng.auth.session.SessionService
import com.donghaeng.auth.session.SessionToken
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Turns a completed round trip at any provider into an `app_user` and a session.
 *
 * The order of the two lookups in [resolveUserId] is the whole design, and it is the
 * order `#82` exists about: the provider subject first, the verified email only if
 * that misses (inside [AccountRegistrationService]), an account only if both do.
 *
 * **A first login is idempotent** (2026-08-13, `#93`). Two of them arriving at once
 * both take the create branch, and one of them loses at the identity's own unique
 * index; losing that race is not an error, it is the news that the first attempt
 * registered — so this resolves again and continues as a login, up to the bound
 * [resolveUserId] proves. The only thing that serialises is the index row itself:
 * two different people signing in at the same instant take no shared lock and never
 * meet.
 *
 * Nothing here names a provider. Which one spoke is carried by
 * [ProviderProfile.provider], so `#89` adds mappers and changes no logic in THIS
 * file — which is a claim about this file only. `#89` still restructures
 * [com.donghaeng.auth.oauth.OAuthLoginSuccessHandler], because Naver is plain
 * OAuth 2.0 and produces no `OidcUser` to map from
 * (notes/2026-08-12-decision-login-slice-by-provider.md).
 */
@Service
internal class LoginService(
    private val identities: OauthIdentityRepository,
    private val registrations: AccountRegistrationService,
    private val sessions: SessionService,
) {
    /**
     * **Not `@Transactional`, and that is load-bearing.** Each step below commits on
     * its own, because the retry has to see rows another transaction committed after
     * this login started — which a surrounding transaction would either hide or, in
     * the failing case, poison ([AccountRegistrationService]).
     *
     * What that costs is stated plainly: a crash between the account and the session
     * leaves an account with no session, and the next login picks it up. The
     * alternative buys atomicity for a pair that does not need it and gives up the
     * thing `#93` is about.
     */
    fun login(
        profile: ProviderProfile,
        presented: SessionToken?,
        now: Instant = Instant.now(),
    ): SessionToken {
        val userId = resolveUserId(profile, now)
        return sessions.issue(userId, presented, now)
    }

    /**
     * **Three passes, and three is a bound rather than a helping.** A pass can lose
     * on either index, and the two behave differently on the pass that follows:
     *
     * - Losing on `ux_oauth_identity_provider_subject` means the winning
     *   `oauth_identity` is committed. Nothing ever deletes one — the table has no
     *   `deleted_at` and no delete path — so the NEXT pass's subject lookup finds it
     *   and returns. An identity loss is always the last loss.
     * - Losing on `ux_app_user_email` means an `app_user` holding this merge key is
     *   committed, equally undeletably. So the next pass's merge lookup finds it and
     *   reaches the identity insert, which means **that pass cannot lose on the
     *   email index again**; it either succeeds or loses on the identity index.
     *
     * The worst path is therefore email, then identity, then resolved — which is
     * exactly the interleaving two rivals holding one uncommitted row each produce,
     * and it was staged and reproduced rather than reasoned about. An earlier
     * version of this loop stopped at two and answered that case with the masked 500
     * `#93` exists to remove.
     *
     * The catch is narrow ([IdentityCollision]): anything else the schema refuses
     * still fails, loudly.
     */
    private fun resolveUserId(
        profile: ProviderProfile,
        now: Instant,
    ): Long {
        var collision: DataIntegrityViolationException? = null
        repeat(ATTEMPTS) {
            identities.findByProviderAndProviderUserId(profile.provider, profile.subject)?.let { identity ->
                return identity.userId
            }
            try {
                return registrations.register(profile, now)
            } catch (failure: DataIntegrityViolationException) {
                if (!IdentityCollision.alreadyRegistered(failure)) throw failure
                collision = failure
            }
        }
        // Rethrown rather than replaced: this is now a genuine 500, and the cause is
        // the only thing that says which index refused it.
        throw checkNotNull(collision)
    }

    private companion object {
        const val ATTEMPTS = 3
    }
}
