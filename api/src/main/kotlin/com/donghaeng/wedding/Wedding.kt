package com.donghaeng.wedding

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.DynamicUpdate
import org.hibernate.annotations.SQLRestriction
import java.time.Instant
import java.time.LocalDate

/**
 * The top-level unit of the product: a couple's ledger is a wedding, and a person
 * reaches one only by holding one of its two [WeddingSeat]s.
 *
 * **The couple's names are not here** (changed 2026-08-22,
 * notes/2026-08-22-decision-the-couples-two-seats.md). `groom_name` and `bride_name`
 * were a person's attribute stored as the wedding's, with nothing joining either to
 * the account that person logs in with; they moved onto the seats, and were dropped
 * rather than left in place because a name column nothing reads is a name column
 * someone writes to.
 *
 * **[createdBy] is a `Long`, not an `AppUser`.** A mapping would make this class
 * depend on `auth/`'s rows, which the architecture forbids; the foreign key still
 * exists in the database and nothing here needs to load the person.
 *
 * **[guaranteedHeadcount] is NULL until the couple says otherwise, and can go back
 * to being NULL.** `#151` publishes it beside the 식대 인원 and `#173` is the write
 * path; a row that has never met a venue contract and a row whose contract fell
 * through are the same state, and the headcount omits the member for both
 * (notes/2026-08-21-decision-the-headcount-endpoint.md §2,
 * notes/2026-08-22-decision-partial-update-shape.md).
 *
 * The timestamps have no defaults: the service is the only clock
 * ([WeddingService.create]), so a row cannot be written with a `created_at` from
 * whenever the object happened to be constructed.
 *
 * `@DynamicUpdate` is what confines a race to the one column it was about
 * (notes/2026-08-20-decision-row-concurrency-and-the-audit-trail.md). Without it
 * `#8` setting [guaranteedHeadcount] alone emits a full-column UPDATE that
 * blind-writes [weddingDate] from the snapshot its transaction loaded — and `wedding`
 * has no `guest_change` trail to recover an overwritten value from, so "last write
 * wins is accepted" never covered it.
 */
@Entity
@DynamicUpdate
@Table(name = "wedding")
@SQLRestriction("deleted_at is null")
internal class Wedding(
    // `var` since #173: 예식일 is editable in 설정, and it is the one column of this
    // row a partial update may write without being able to clear it.
    @Column(name = "wedding_date", nullable = false)
    var weddingDate: LocalDate,
    @Column(name = "created_by", nullable = false, updatable = false)
    val createdBy: Long,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
    // 보증인원 — the VENUE's number, never ours, and nullable because a couple signs
    // up before booking one, and because a couple who has un-signed goes back to
    // having none: `PATCH /weddings/{weddingId}` sets it and clears it (#173).
    @Column(name = "guaranteed_headcount")
    var guaranteedHeadcount: Int? = null,
    @Column(name = "deleted_at")
    var deletedAt: Instant? = null,
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long = 0,
)
