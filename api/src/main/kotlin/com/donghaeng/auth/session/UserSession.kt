package com.donghaeng.auth.session

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.DynamicUpdate
import java.time.Duration
import java.time.Instant

/**
 * One issued session. The row the cookie's `<selector>.<verifier>` resolves to;
 * see `V2__user_session.sql` for why the token has two halves.
 *
 * Nothing here can be replayed if the table leaks: [selector] carries no
 * authority on its own and [verifierHash] is a hash.
 *
 * ## Why `@DynamicUpdate`, which is a correctness fix rather than a performance one
 *
 * By default Hibernate writes **every** updatable column of a dirty entity from
 * the snapshot that transaction loaded. Two of this row's columns are written by
 * two different operations that legitimately race — `last_seen_at` by a resolve,
 * `revoked_at` by a logout — so under READ COMMITTED the resolve's UPDATE carried
 * `revoked_at = null` from a snapshot taken before the logout committed, and
 * **silently un-revoked the session** while the caller was told 204.
 *
 * The shape is not exotic: someone opens the app on a device after days away and
 * taps sign-out while the page's first `/auth/me` is still in flight. That is the
 * walked-away-device case logout exists for, and the consequence was a token that
 * outlived its own logout for the rest of its 180 days — on the one device they
 * cannot reach again.
 *
 * It also defeats the sentence the lifetime record buys the new numbers with:
 * "the row can be marked and the token dies on the next request"
 * (notes/2026-08-12-decision-session-lifetimes.md). Held by a two-transaction
 * test; nothing else would notice this regressing.
 *
 * **The criterion for the next entity, and the limit that comes with it.** The
 * reasoning generalises: any row whose columns are written by more than one
 * operation wants this, and in this domain that is most of them — `guest` alone
 * has attendance, meal counts and a soft delete arriving by different paths.
 *
 * But it closes **column-disjoint** races only. Two operations writing the SAME
 * column still end in last-writer-wins, and `@DynamicUpdate` will not say so —
 * two people tapping attendance on one 하객 from two phones is the couple's
 * ordinary usage, not an edge case, and that needs `@Version`. Copying this
 * annotation forward while believing concurrency is handled would be worse than
 * not copying it: unknown debt turned into false confidence. The row-level
 * concurrency policy is a decision this stop does not make.
 */
@Entity
@DynamicUpdate
@Table(name = "user_session")
internal class UserSession(
    @Column(name = "selector", nullable = false, length = 32)
    val selector: String,
    @Column(name = "verifier_hash", nullable = false, length = 64)
    val verifierHash: String,
    @Column(name = "user_id", nullable = false)
    val userId: Long,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "last_seen_at", nullable = false)
    var lastSeenAt: Instant,
    @Column(name = "revoked_at")
    var revokedAt: Instant? = null,
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long = 0,
) {
    /**
     * Both expiries, and they are two different questions: [idle] is measured from
     * the last request, [absolute] from the moment the session was issued. A
     * configuration file can express the first and cannot express the second, so
     * this is where the second lives (#5's handover comment, carried to #37 by
     * notes/2026-08-12-decision-login-slice-by-provider.md).
     */
    fun isUsableAt(
        now: Instant,
        idle: Duration,
        absolute: Duration,
    ): Boolean =
        revokedAt == null &&
            now.isBefore(lastSeenAt.plus(idle)) &&
            now.isBefore(createdAt.plus(absolute))
}
