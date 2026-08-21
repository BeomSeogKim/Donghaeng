package com.donghaeng.wedding

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.SQLRestriction
import java.time.Instant
import java.time.LocalDate

/**
 * The top-level unit of the product: a couple's ledger is a wedding, and a person
 * reaches one only through a [Membership].
 *
 * **[createdBy] is a `Long`, not an `AppUser`.** A mapping would make this class
 * depend on `auth/`'s rows, which the architecture forbids; the foreign key still
 * exists in the database and nothing here needs to load the person.
 *
 * **[guaranteedHeadcount] is read and never written here.** `#151` publishes it
 * beside the 식대 인원, and `#8` is what will let the couple set it; until then it is
 * NULL on every row and the headcount simply omits the member
 * (notes/2026-08-21-decision-the-headcount-endpoint.md §2).
 *
 * The timestamps have no defaults: the service is the only clock
 * ([WeddingService.create]), so a row cannot be written with a `created_at` from
 * whenever the object happened to be constructed.
 */
@Entity
@Table(name = "wedding")
@SQLRestriction("deleted_at is null")
internal class Wedding(
    @Column(name = "wedding_date", nullable = false)
    val weddingDate: LocalDate,
    @Column(name = "groom_name", nullable = false, length = 100)
    var groomName: String,
    @Column(name = "bride_name", nullable = false, length = 100)
    var brideName: String,
    @Column(name = "created_by", nullable = false, updatable = false)
    val createdBy: Long,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
    // 보증인원 — the VENUE's number, never ours, and nullable because a couple signs
    // up before booking one. `var` because `#8` edits it; nothing in this tree
    // assigns to it yet.
    @Column(name = "guaranteed_headcount")
    var guaranteedHeadcount: Int? = null,
    @Column(name = "deleted_at")
    var deletedAt: Instant? = null,
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long = 0,
)
