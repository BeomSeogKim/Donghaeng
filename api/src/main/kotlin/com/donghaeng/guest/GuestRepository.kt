package com.donghaeng.guest

import com.donghaeng.wedding.WeddingSide
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param

/**
 * **Deliberately not a `JpaRepository`**, which is where the ledger differs from
 * `wedding/`: `findById`, `getReferenceById`, `existsById` and `deleteById` are all
 * keyed on the primary key alone, and `#12`, `#14` and `#15` address a guest by a
 * `guestId` the caller chose. The scope gate proves the WEDDING is the caller's and
 * says nothing about whose guest that id is, so an inherited `findById(guestId)`
 * would compile, read and return another wedding's row with the whole suite green.
 *
 * Every method is therefore declared here, and one taking a `guestId` takes the
 * wedding with it.
 */
internal interface GuestRepository : Repository<Guest, Long> {
    fun save(guest: Guest): Guest

    /**
     * The ledger, filtered by the only two axes it has (`#15`), in one query and one
     * response — **this endpoint does not page**, and `docs/api-spec.md` carries the
     * reasoning `web/` needs.
     *
     * Three things about the JPQL are load-bearing.
     *
     * **`deleted_at is null` is spelled out**, as it is in `WeddingRepository` and
     * for the same reason: `@SQLRestriction` would add it, but the condition that
     * decides whether a removed 하객 is back on the couple's screen belongs where
     * the query is read (notes/2026-08-10-decision-soft-delete.md).
     *
     * **The attendance predicate is `coalesce(confirmed, expected)`** — the same
     * fallback the headcount sums (notes/2026-08-05-design-meal-headcount.md §1).
     * The ledger and the headcount are one screen, so a filter that read
     * `expected_attending` alone would show a guest under 참석 while the number
     * beside it counted them 불참. Nothing writes a confirmed value until `#13`, so
     * this is written against a column that is still always NULL — deliberately,
     * because the alternative is a predicate that silently changes meaning the day
     * it stops being.
     *
     * **Each filter arrives as the set of values it accepts — never as a nullable
     * "or null" parameter**, and that is a working constraint rather than a taste.
     * `side` is a Postgres `wedding_side` column, so `(:side is null or …)` binds an
     * untyped NULL and the server answers `could not determine data type of
     * parameter`: a 500 on the ledger's own screen, and only on the unfiltered
     * request. An unfiltered call passes every value instead, which is a predicate
     * that narrows nothing. Neither set is ever empty — an empty one would render
     * `in ()`, which is not SQL — and [GuestService.list] is where that is
     * guaranteed.
     */
    @Query(
        """
        select g from Guest g
        where g.weddingId = :weddingId
          and g.deletedAt is null
          and g.side in :sides
          and coalesce(g.confirmedAttending, g.expectedAttending) in :attendance
        order by g.createdAt, g.id
        """,
    )
    fun findLedger(
        @Param("weddingId") weddingId: Long,
        @Param("sides") sides: Set<WeddingSide>,
        @Param("attendance") attendance: Set<Boolean>,
    ): List<Guest>
}
