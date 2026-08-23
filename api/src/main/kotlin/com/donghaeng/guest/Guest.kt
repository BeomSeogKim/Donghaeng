package com.donghaeng.guest

import com.donghaeng.wedding.WeddingSide
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.SQLRestriction
import org.hibernate.type.SqlTypes
import java.time.Instant

/**
 * A row in the ledger: a person the couple expects at their wedding.
 *
 * **[weddingId] is a root marker, not the integrity device `guest_meal_count`'s is**
 * (api/AGENTS.md, Domain mechanisms) — the ledger is queried by wedding on every
 * screen. It is a `Long` rather than an association for the reason [createdBy] is:
 * a mapping would make this class depend on another domain's rows.
 *
 * **The expected slots are NOT NULL and the confirmed slots are nullable, and the
 * asymmetry is the model** — a blank confirmed slot means UNKNOWN, never zero
 * (notes/2026-08-03-design-domain-model.md §1).
 *
 * **[companionOf] is what replaced `expected_party_size`** (2026-08-23, `#213`,
 * notes/2026-08-23-decision-companions-become-guests.md). A party of three is three
 * of these rows, not one row carrying a `3`. What that buys is the two things a
 * count could not express — a companion on the other 측, and a head who cannot come
 * while their companion still can — and what it costs is that 측을 물려받고 불참을
 * 따라간다 stop being facts of the model and become **defaults applied at creation**.
 * Nothing enforces them afterwards, deliberately: each guest moves on its own, which
 * is the whole point.
 *
 * **It is `val`, and that is a narrower claim than it looks.** Nothing in v1 moves a
 * guest between parties, so the column is written once by [GuestService.create]; the
 * day an edit does, this becomes a `var` and the composite FK below is what keeps it
 * inside one wedding.
 *
 * `guest_meal_count` is deliberately unmapped: it references `meal_type` rows only
 * `#10` can create. `validate` compares mapped columns only, so its absence is not
 * drift. The timestamps carry no defaults; [GuestService] is the only clock.
 */
@Entity
@Table(name = "guest")
@SQLRestriction("deleted_at is null")
internal class Guest(
    @Column(name = "wedding_id", nullable = false, updatable = false)
    val weddingId: Long,
    @Column(name = "name", nullable = false, length = 100)
    var name: String,
    // NAMED_ENUM, not STRING: `side` is a Postgres `wedding_side` column, and a
    // plain string bind is rejected by the server as a type mismatch.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "side", nullable = false, columnDefinition = "wedding_side")
    var side: WeddingSide,
    @Enumerated(EnumType.STRING)
    @Column(name = "group_category", nullable = false, length = 30)
    var groupCategory: GuestGroupCategory,
    @Column(name = "group_label", length = 100)
    var groupLabel: String?,
    @Column(name = "contact", length = 30)
    var contact: String?,
    @Column(name = "accessibility_note", length = 500)
    var accessibilityNote: String?,
    @Column(name = "expected_attending", nullable = false)
    var expectedAttending: Boolean,
    @Column(name = "created_by", nullable = false, updatable = false)
    val createdBy: Long,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
    @Column(name = "updated_by", nullable = false)
    var updatedBy: Long,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
    // The guest who brought this one, NULL on a head (2026-08-23, `#213`). A `Long`
    // and not a self-association: `open-in-view: false` makes a lazy `@ManyToOne`
    // read after the transaction a `LazyInitializationException`, and nothing here
    // needs to load the head — the ledger folds a list it already has in hand.
    @Column(name = "companion_of", updatable = false)
    val companionOf: Long? = null,
    @Column(name = "confirmed_attending")
    var confirmedAttending: Boolean? = null,
    @Column(name = "confirmed_party_size")
    var confirmedPartySize: Int? = null,
    @Column(name = "deleted_at")
    var deletedAt: Instant? = null,
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long = 0,
)
