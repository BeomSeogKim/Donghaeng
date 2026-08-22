package com.donghaeng.wedding

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.DynamicUpdate
import java.time.Instant

/**
 * A minted invite: the one credential that can fill a wedding's empty seat
 * (notes/2026-08-22-decision-the-partner-invite.md).
 *
 * **It points at a SEAT and carries no `wedding_id`**, which is an answer to the
 * standing rule rather than an omission of it — `V4__wedding_invite.sql` argues it in
 * full. The short version: no id here ever arrives from a request, so there is no
 * integrity hole for a duplicated `wedding_id` to close, and this row is only ever read
 * by [selector] or by [seatId].
 *
 * **[verifierHash] is the whole of what is stored**, so this table cannot be turned
 * back into a working link. [InviteToken] holds every rule about that.
 *
 * Three columns say how an invite's life ends and they are not interchangeable:
 * [expiresAt] is the day it goes stale unopened, [acceptedAt] is it being spent, and
 * [revokedAt] is 재발급 killing it. There is deliberately no `deleted_at` — nothing
 * here is a row a user deletes, so notes/2026-08-10-decision-soft-delete.md and its
 * consequences do not apply.
 *
 * `@DynamicUpdate` because the two paths that end an invite write one column each, and
 * a full-column UPDATE would blind-write the other from whatever snapshot the
 * transaction loaded (notes/2026-08-20-decision-row-concurrency-and-the-audit-trail.md).
 */
@Entity
@DynamicUpdate
@Table(name = "wedding_invite")
internal class WeddingInvite(
    @Column(name = "seat_id", nullable = false, updatable = false)
    val seatId: Long,
    @Column(name = "selector", nullable = false, updatable = false, length = 32)
    val selector: String,
    @Column(name = "verifier_hash", nullable = false, updatable = false, length = 64)
    val verifierHash: String,
    @Column(name = "issued_by", nullable = false, updatable = false)
    val issuedBy: Long,
    @Column(name = "issued_at", nullable = false, updatable = false)
    val issuedAt: Instant,
    @Column(name = "expires_at", nullable = false, updatable = false)
    val expiresAt: Instant,
    @Column(name = "accepted_at")
    var acceptedAt: Instant? = null,
    @Column(name = "accepted_by")
    var acceptedBy: Long? = null,
    @Column(name = "revoked_at")
    var revokedAt: Instant? = null,
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long = 0,
) {
    /** Spent. Asked before [wasSuperseded] because one row can carry both and this one wins. */
    fun wasSpent(): Boolean = acceptedAt != null

    /** Replaced by a 재발급 (notes/2026-08-22-decision-the-superseded-link-speaks.md). */
    fun wasSuperseded(): Boolean = revokedAt != null

    /** Three deaths, three questions — the caller is told each of them apart. */
    fun hasExpiredAt(now: Instant): Boolean = !now.isBefore(expiresAt)
}
