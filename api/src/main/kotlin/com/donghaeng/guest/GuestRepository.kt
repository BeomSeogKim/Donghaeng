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
     * response. What it means and why it does not page is
     * `notes/2026-08-20-decision-the-ledger-read-and-its-filters.md`, published for
     * `web/` in `docs/api-spec.md`; [AttendanceFilter] carries what the `coalesce`
     * means, and [sumAttendingPartySize] is the number reading the same expression.
     * What is here is what the SQL itself has to get right.
     *
     * **`deleted_at is null` is spelled out**, as it is in `WeddingRepository` and
     * for the same reason: `@SQLRestriction` would add it, but the condition that
     * decides whether a removed 하객 is back on the couple's screen belongs where
     * the query is read (notes/2026-08-10-decision-soft-delete.md).
     *
     * **Each filter arrives as the set of values it accepts — never as a nullable
     * "or null" parameter**, and that is a working constraint rather than a taste.
     * `side` is a Postgres `wedding_side` column, so `(:side is null or …)` binds an
     * untyped NULL and the server answers `could not determine data type of
     * parameter`: a 500 on the ledger's own screen, and only on the request that
     * sends no filter at all. An unfiltered call passes every value instead, which
     * is a predicate that narrows nothing. Neither set may be empty — an empty one
     * renders `in ()`, which is not SQL — and [GuestService.list] is what guarantees
     * that.
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

    /**
     * 식대 인원 (`#151`, the backend half of `#17`) — **the one query in this file
     * whose failure mode is a wrong number rather than a wrong list.** A wrong
     * predicate here does not throw; it prints money
     * (notes/2026-08-21-decision-the-headcount-endpoint.md).
     *
     * Three conditions, and each one is a decision someone could undo without
     * noticing.
     *
     * **`deleted_at is null`, spelled out** for the reason [findLedger] spells it
     * out, and stated honestly: this is JPQL, so `@SQLRestriction` already adds the
     * same condition and deleting this line changes no number today — verified by
     * deleting it. It is here because the condition that decides whether a removed
     * 하객 is inside a money number belongs where the query is read, and because the
     * rewrite this query is already scheduled for (`#14`, below) is the shape the
     * ambient filter stops reaching. The test that holds it is not decoration for the
     * same reason: it goes red the day this becomes a native join that forgot it, and
     * that failure is silent everywhere else.
     *
     * **Attendance is read before party size**: 불참이면 party size가 몇이든 0이다
     * (notes/2026-08-20-decision-guest-entry-side-and-companions.md §3), which is
     * why this is a predicate rather than a `case`. It reads the SAME
     * `coalesce(confirmed, expected)` [findLedger] filters on, and that is the whole
     * point of the expression: 원장과 인원수는 한 화면이라 the chip and the number may
     * not disagree, and one axis read two ways is how they eventually would. Nothing
     * writes `confirmed_attending` in v1
     * (notes/2026-08-21-decision-attendance-is-two-states.md), so this is exactly
     * `expected_attending` today.
     *
     * **JPQL over `Guest`, not native SQL over `guest_meal_count`.** That table has
     * no rows until `#10`/`#14`, and its `wedding_id` is an integrity column that
     * must never become a predicate — the natural native query counts a deleted
     * 하객's meals (api/AGENTS.md, Domain mechanisms). When `#14` lands, the rule is
     * "the guest's rows if they have any, else the party size", and the join is on
     * `guest`.
     */
    @Query(
        """
        select coalesce(sum(g.expectedPartySize), 0) from Guest g
        where g.weddingId = :weddingId
          and g.deletedAt is null
          and coalesce(g.confirmedAttending, g.expectedAttending) = true
        """,
    )
    fun sumAttendingPartySize(
        @Param("weddingId") weddingId: Long,
    ): Long
}
