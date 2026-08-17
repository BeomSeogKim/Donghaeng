package com.donghaeng.auth.account

import org.postgresql.util.PSQLException
import org.springframework.dao.DataIntegrityViolationException
import java.sql.SQLException

/**
 * "Someone registered this identity while we were registering it" — and nothing
 * else.
 *
 * `#93`'s create path continues as a login when the INSERT it just lost was lost to
 * the identity's own index. That recovery is only safe if the question is answered
 * NARROWLY: `app_user` and `oauth_identity` between them carry two CHECK
 * constraints, a verifier allowlist and two foreign keys, and every one of those
 * arrives as the same [DataIntegrityViolationException]. Reading one of them as
 * "already exists" would retry a row the schema refuses — and on the `app_user`
 * side it would end with a session issued against someone else's account, because
 * the retry resolves whatever row now holds the merge key.
 *
 * So the match is on the CONSTRAINT, and it is read from the wire rather than from
 * the error text. Postgres reports the constraint name as its own protocol field
 * ('n' in the ErrorResponse message), which [PSQLException] exposes verbatim;
 * Hibernate's `ConstraintViolationException.getConstraintName()` would have saved
 * the driver import but arrives at the same string by matching an ENGLISH message
 * template, so a server whose `lc_messages` is not English extracts nothing and
 * every collision quietly stops being recognised. That is the failure `#93` already
 * is.
 */
internal object IdentityCollision {
    /**
     * The two indexes that mean "this person already has an account", one per
     * identity axis (2026-08-13, `#93`):
     *
     * - `ux_oauth_identity_provider_subject` — the same provider subject. The other
     *   attempt registered the same social account.
     * - `ux_app_user_email` — the same verified email, which is the merge key
     *   (2026-08-11 §A). The other attempt committed the `app_user` this login must
     *   now merge onto, exactly as a sequential second provider would.
     *
     * Neither is partial on `deleted_at`: `app_user` and `oauth_identity` are not
     * user-deletable and carry no such column (2026-08-10), so a violation here
     * always names a LIVE row and never a soft-deleted one that a re-registration
     * should be allowed past.
     *
     * **Not private, because two hardcoded index names are exactly the kind of thing
     * that rots silently here.** Every DDL statement against a real database is
     * typed by hand and `ddl-auto: validate` sees nothing about indexes
     * (api/AGENTS.md, Schema ownership), so a rename would make this set match
     * nothing, restore `#93` in full, and leave the suite green.
     * `IdentityCollisionSchemaTest` asserts it EQUALS the schema's own unique
     * indexes on those two tables — equality, so that a third one added later forces
     * a decision about what it means instead of defaulting into the silent branch.
     */
    val ALREADY_REGISTERED =
        setOf(
            "ux_oauth_identity_provider_subject",
            "ux_app_user_email",
        )

    private const val UNIQUE_VIOLATION = "23505"

    /**
     * A cause chain is bounded in practice at three (Spring, Hibernate, driver);
     * this is the depth beyond which one is a cycle rather than a chain. A cycle of
     * two mutually-referring exceptions would otherwise spin the request thread
     * forever, which the self-reference guard below does not catch.
     */
    private const val CAUSE_DEPTH = 16

    fun alreadyRegistered(failure: DataIntegrityViolationException): Boolean {
        val violation =
            failure
                .causeChain()
                .take(CAUSE_DEPTH)
                .filterIsInstance<PSQLException>()
                .firstOrNull() ?: return false
        if (violation.sqlState != UNIQUE_VIOLATION) return false
        return violation.serverErrorMessage?.constraint in ALREADY_REGISTERED
    }

    /**
     * The driver's exception sits two wrappers down (Spring, then Hibernate).
     * [SQLException.getNextException] is walked as well because a failure inside a
     * JDBC batch keeps the real one there rather than in `cause` — Hibernate does
     * not batch these IDENTITY inserts today, and a change that made it do so must
     * not silently turn this predicate to `false`.
     *
     * A driver that reports itself as its own cause — which is how some report "no
     * cause" — must not turn a login into an infinite walk, hence the identity
     * check.
     */
    private fun Throwable.causeChain(): Sequence<Throwable> =
        generateSequence(this) { current ->
            (current.cause ?: (current as? SQLException)?.nextException)?.takeIf { it !== current }
        }
}
