package com.donghaeng.wedding

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.SQLRestriction
import java.time.Instant

/**
 * Who may reach a [Wedding]. **A wedding with no membership is a wedding nobody can
 * reach**, which is why the first one is written in the same transaction as the
 * wedding itself ([WeddingService.create]).
 *
 * Its own aggregate root — `#5` queries it by `user_id` with no wedding in hand — so
 * `wedding_id` here is a root marker, not an integrity column.
 *
 * **`#5`'s `user → membership → wedding` resolver belongs in this package**, not in
 * `auth/session/`: `auth/` answers who is asking, and which wedding is a question
 * about these rows. It reaches them through a service rather than this repository
 * directly, because an argument resolver is an inbound edge and the layer rule lets
 * only a service touch persistence — no change to `ArchitectureTest` is needed for
 * it to land.
 */
@Entity
@Table(name = "membership")
@SQLRestriction("deleted_at is null")
internal class Membership(
    @Column(name = "wedding_id", nullable = false, updatable = false)
    val weddingId: Long,
    @Column(name = "user_id", nullable = false, updatable = false)
    val userId: Long,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
    @Column(name = "deleted_at")
    var deletedAt: Instant? = null,
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long = 0,
)
