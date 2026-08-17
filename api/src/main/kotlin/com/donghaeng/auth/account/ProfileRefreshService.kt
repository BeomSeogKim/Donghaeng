package com.donghaeng.auth.account

import org.apache.commons.logging.LogFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/**
 * What a returning login is allowed to learn about a person from the provider: the
 * display name, and nothing else (`#94`, first half).
 *
 * Without this, the name we happened to see on someone's first login is the name we
 * show them forever — a rename at Google never arrives, because every later login
 * recognises the identity row and stops.
 *
 * **It writes only when the value actually differs**, and the comparison happens in
 * the statement ([AppUserRepository.renameIfChanged]) rather than here. Login is the
 * most frequent write path this application will have; a blind `set name = :name`
 * would turn every sign-in into a new row version, a WAL record and dead tuples for
 * autovacuum, for a value that changes perhaps once in a person's lifetime.
 *
 * **An absent name is not an instruction to erase one.** A provider that stops
 * sending the field — Kakao's nickname is a separate consent item, so `#89` meets
 * this immediately — must leave the stored name standing.
 *
 * **`email` and `email_verified_by` are not this class's business.** Giving an
 * account a verified address it never had is `#94`'s other half and needs a
 * verification step we do not have yet (`#110`); the retry `#93` builds on
 * `ux_app_user_email` stays sound only while nothing writes that column without one
 * (2026-08-13 record, 2026-08-17 section). `AppUserWriteScopeTest` holds the columns
 * rather than this paragraph.
 *
 * **A refresh is best-effort, and that is the design rather than a caveat.** A
 * display name is not worth a login: a value Postgres refuses — a NUL byte is the
 * realistic one, since [com.donghaeng.auth.oauth.GoogleProfile] already bounds the
 * length — or a lock timeout would otherwise 500 that person's every login from then
 * on, which is exactly the "they would fail every login attempt, forever, for having
 * a long display name" that mapper refuses to accept one property over.
 *
 * **That is why the transaction is a [TransactionTemplate] and not `@Transactional`.**
 * A statement that fails inside a Spring-managed transaction marks it rollback-only,
 * so an annotated method that caught its own exception would still throw
 * `UnexpectedRollbackException` at the boundary the caller cannot see — the catch has
 * to enclose the commit, which means holding the boundary here. The propagation is
 * the same `REQUIRES_NEW` the annotation would have declared: a login is a chain of
 * independent commits (2026-08-13 record, 2026-08-17 section), and this is a third
 * one rather than part of anybody's transaction.
 *
 * Nothing here names a provider, so `#89` adds mappers and leaves this file alone.
 */
@Service
internal class ProfileRefreshService(
    transactions: PlatformTransactionManager,
    private val users: AppUserRepository,
) {
    private val logger = LogFactory.getLog(javaClass)

    private val ownCommit =
        TransactionTemplate(transactions).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    fun refresh(
        userId: Long,
        profile: ProviderProfile,
        now: Instant = Instant.now(),
    ) {
        // Blank and absent are the same answer: a provider that hands back `""` for
        // a field it has nothing for would otherwise erase a name through a door
        // `null` is already barred from.
        val name = profile.name?.takeIf(String::isNotBlank) ?: return
        try {
            ownCommit.executeWithoutResult { users.renameIfChanged(userId, name, now) }
        } catch (failure: RuntimeException) {
            // The name is deliberately absent from this line, and so is the
            // throwable: our own id and the failure's type are what an incident
            // needs, while pgjdbc copies Postgres's DETAIL — the failing row, which
            // here is the name and the merge key — into its message (`#64`).
            logger.warn("profile refresh failed for app_user $userId (${failure.javaClass.name}); the login is unaffected")
        }
    }
}
