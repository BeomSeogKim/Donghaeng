package com.donghaeng.wedding

import org.postgresql.util.PSQLException
import org.springframework.core.NestedExceptionUtils
import org.springframework.dao.DataIntegrityViolationException

/**
 * "Someone took this person's one seat slot while we were taking it" — and nothing
 * else.
 *
 * Since 2026-08-21 the database holds 한 사람은 웨딩 하나 itself, as a partial unique
 * index (`ux_party_user`, carried over from `membership` unchanged by `V3`). That
 * changes what the loser of a race gets: it used to be a second seat, and it is now
 * an INSERT that fails. **A caller who loses the race must be told exactly what a
 * caller who simply already had a wedding is told** — from their side it is the same
 * fact, and the recovery `docs/api-spec.md` publishes for `ALREADY_IN_A_WEDDING` is
 * the same one. Untranslated it would surface as a masked 500, which reads as "we are
 * broken" and invites a retry that can only fail again.
 *
 * **Narrow, on the constraint, read from the wire.** `wedding_party` also carries two
 * foreign keys and a second unique index, and every one of them arrives as the same
 * [DataIntegrityViolationException]; answering 409 to a foreign-key violation would
 * tell a caller they are already in a wedding when the truth is that we wrote a row
 * we should not have. Postgres names the constraint in its own protocol field, which
 * [PSQLException] exposes verbatim — the same reading, and for the same reason, as
 * `IdentityCollision` in `auth/account`.
 *
 * It is allowed to be shorter than that one. This is a single-row insert with an
 * IDENTITY key, so it is never batched and the driver's exception is never parked in
 * `nextException`; and it is a BACKSTOP behind [WeddingService.claimSoleSeat]'s
 * advisory lock rather than the only thing standing there, so a shape it fails to
 * recognise costs a 500 on a path the lock already keeps unreachable — where
 * `IdentityCollision` missing one costs `#93` in full.
 */
internal object SoleSeatCollision {
    /**
     * The index that means "this person already holds a seat", partial on
     * `deleted_at is null` so that a released seat's dead row is not it
     * (notes/2026-08-10-decision-soft-delete.md), and on `user_id is not null` so
     * that an unclaimed seat is legal
     * (notes/2026-08-22-decision-the-couples-two-seats.md §3).
     *
     * **Not private, because a hardcoded index name rots silently here**: every DDL
     * statement against a real database is typed by hand and `ddl-auto: validate`
     * sees nothing about indexes (api/AGENTS.md, Schema ownership), so a rename would
     * make this match nothing, turn the race loser's 409 into a 500, and leave the
     * suite green. `WeddingSeatSchemaTest` asserts it names a real index and asserts
     * what that index enforces.
     */
    val SOLE_SEAT_INDEX = "ux_party_user"

    private const val UNIQUE_VIOLATION = "23505"

    fun slotAlreadyTaken(failure: DataIntegrityViolationException): Boolean {
        val violation = NestedExceptionUtils.getMostSpecificCause(failure) as? PSQLException ?: return false
        if (violation.sqlState != UNIQUE_VIOLATION) return false
        return violation.serverErrorMessage?.constraint == SOLE_SEAT_INDEX
    }
}
