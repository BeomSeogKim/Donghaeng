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
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.SQLRestriction
import org.hibernate.type.SqlTypes
import java.time.Instant

/**
 * One of a [Wedding]'s two seats — 신랑 and 신부 — carrying that person's [name] and,
 * once they arrive, their account (`notes/2026-08-22-decision-the-couples-two-seats.md`).
 *
 * **[userId] is the membership**: a person may reach a wedding exactly when they hold
 * one of its live seats, so `user → seat → wedding` is the walk every scoped request
 * runs ([WeddingService.scopeFor]). `null` means the seat is still waiting, which is
 * the state that makes a wedding created by one person complete rather than
 * half-built — and it is why `#9`'s invite is an UPDATE of an identified row rather
 * than an insert.
 *
 * Its own aggregate root — `#5` queries it by `user_id` with no wedding in hand — so
 * `wedding_id` here is a root marker, not an integrity column.
 *
 * The table is `wedding_party`: the party is the pair, a row is a seat in it. Nothing
 * in this tree may call either one a membership again, because that word is what
 * would invite the next person to hang a price plan off a per-person row
 * (`notes/2026-08-22-decision-entitlement-belongs-to-the-wedding.md` §2).
 *
 * `NAMED_ENUM` for [side] because the column is a Postgres `wedding_side`, exactly as
 * `Guest.side` is; a plain string bind is rejected by the server as a type mismatch.
 *
 * `@DynamicUpdate` because `#9` writes [userId], [joinedAt] and possibly [name] one
 * at a time (`notes/2026-08-20-decision-row-concurrency-and-the-audit-trail.md`), and
 * a full-column UPDATE would blind-write the other seat members from whatever
 * snapshot the transaction loaded.
 */
@Entity
@DynamicUpdate
@Table(name = "wedding_party")
@SQLRestriction("deleted_at is null")
internal class WeddingSeat(
    @Column(name = "wedding_id", nullable = false, updatable = false)
    val weddingId: Long,
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "side", nullable = false, updatable = false, columnDefinition = "wedding_side")
    val side: WeddingSide,
    // NULL until that person says who they are — nobody types anybody else's name.
    @Column(name = "name", length = 100)
    var name: String? = null,
    // NULL means the seat is still waiting for its person (`#9`).
    @Column(name = "user_id")
    var userId: Long? = null,
    // When the seat was CLAIMED, which is not created_at: the partner's seat is
    // created with the wedding and claimed whenever they accept.
    @Column(name = "joined_at")
    var joinedAt: Instant? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
    @Column(name = "deleted_at")
    var deletedAt: Instant? = null,
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long = 0,
)
