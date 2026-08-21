package com.donghaeng.wedding

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.DynamicUpdate
import java.time.Instant

/**
 * **A TERM, not a status** — one stretch of time over which one payer held this
 * wedding's entitlement (`notes/2026-08-22-decision-entitlement-belongs-to-the-wedding.md`).
 *
 * 권리는 웨딩에 붙고 결제 주체만 사람이다: the entitlement hangs off [weddingId] so that
 * "one seat can use this ledger and the other cannot" is not representable, and
 * [payerId] records who paid without making the right theirs. That is also why this
 * is not a column on [WeddingSeat] — that table is keyed by seat, so anything on it
 * is per-person by construction.
 *
 * **[endedAt] is not a delete and not a revision.** It appends a later fact to a row
 * whose own values stay true, the same kind `guest_import.superseded_at` carries —
 * which is why this entity has no `deletedAt` and no `@SQLRestriction`: a term is
 * never removed, it ends. When 신랑 stops paying and 신부 starts, the live term ends
 * and a new one opens, so "who paid for July" survives ([SubscriptionService.handOver]).
 *
 * **[currentPeriodEnd] is a third date and a synonym for neither.** It is what the
 * money already paid covers: paid through 8/31 and cancelled on 8/15 leaves the
 * wedding entitled until 8/31. NULL on a free term, and how a gap or an overlap
 * between terms resolves waits on real cancellation and proration rules.
 *
 * [payerId] is a `Long` and not an `AppUser` for the reason `Wedding.createdBy` is —
 * a mapping would make `wedding/` depend on `auth/`'s rows — and it references
 * `app_user` rather than a seat, so that a payer who later releases their seat does
 * not take the record of what they paid for with them.
 */
@Entity
@DynamicUpdate
@Table(name = "wedding_subscription")
internal class WeddingSubscription(
    @Column(name = "wedding_id", nullable = false, updatable = false)
    val weddingId: Long,
    @Enumerated(EnumType.STRING)
    @Column(name = "plan", nullable = false, length = 30)
    var plan: SubscriptionPlan,
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: SubscriptionStatus,
    // NULL = nobody paid for this term, which is exactly what a free term is.
    @Column(name = "payer_id", updatable = false)
    val payerId: Long? = null,
    @Column(name = "started_at", nullable = false, updatable = false)
    val startedAt: Instant,
    // NULL = this is the live term. `ux_subscription_live` is keyed on exactly that.
    @Column(name = "ended_at")
    var endedAt: Instant? = null,
    @Column(name = "current_period_end")
    var currentPeriodEnd: Instant? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long = 0,
)
