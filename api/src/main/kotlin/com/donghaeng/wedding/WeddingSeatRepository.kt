package com.donghaeng.wedding

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * `ux_party_user` serves the `user_id` lookups here — the index `V3` names
 * `user -> seat -> wedding` after, and since 2026-08-21 also the last word on
 * 한 사람은 웨딩 하나. It does not replace [lockSeatSlotOf]: the index decides which
 * rows may exist, the lock decides which of two simultaneous requests gets to ask the
 * question first, and only the lock makes the loser's answer come from a read rather
 * than from a failed INSERT ([SoleSeatCollision]).
 */
internal interface WeddingSeatRepository : JpaRepository<WeddingSeat, Long> {
    fun existsByWeddingIdAndUserIdAndDeletedAtIsNull(
        weddingId: Long,
        userId: Long,
    ): Boolean

    /**
     * Does this person already hold a seat? — 한 사람은 웨딩 하나
     * ([WeddingService.claimSoleSeat], which is the only caller).
     *
     * **`deleted_at is null` is spelled out even though `@SQLRestriction` adds it**,
     * the stance [WeddingRepository] already takes. Here the condition is the whole
     * question: a person whose seat was released keeps the row, and if that row
     * counted they could never have a ledger again
     * (notes/2026-08-10-decision-soft-delete.md).
     *
     * **It cannot match an empty seat**, because a derived `userId = :userId` never
     * matches NULL — which is the same half `ux_party_user` spells as
     * `user_id is not null`.
     */
    fun existsByUserIdAndDeletedAtIsNull(userId: Long): Boolean

    /**
     * **The one row a caller may write**: the seat carrying their own `user_id` in the
     * wedding they were resolved for (`#187`). The pair `(weddingId, callerId)` is the
     * whole of "may I write this" — nobody types anybody else's name
     * (notes/2026-08-22-decision-the-couples-two-seats.md), so the partner's seat is
     * not addressable rather than refused.
     *
     * The same walk [existsByWeddingIdAndUserIdAndDeletedAtIsNull] runs for the scope,
     * so `null` here means the seat was released between the resolver's transaction and
     * this one — which answers 404, exactly as the resolver would have a moment later.
     */
    fun findByWeddingIdAndUserIdAndDeletedAtIsNull(
        weddingId: Long,
        userId: Long,
    ): WeddingSeat?

    /**
     * Both seats of one wedding, for the response — the pair is what replaced
     * `wedding.groom_name` / `bride_name` (2026-08-22), so a wedding cannot be
     * published without reading them.
     *
     * Unordered here and sorted in [toWeddingResponse]: 신랑 먼저 is a property of the
     * published shape, and leaving it to the `wedding_side` type's declaration order
     * would make the seam depend on the order of words inside a `create type`.
     */
    fun findAllByWeddingIdAndDeletedAtIsNull(weddingId: Long): List<WeddingSeat>

    /**
     * The seat an invite points at, locked for the rest of the caller's transaction —
     * `#181`'s accept path, and the row acceptance is an UPDATE of.
     *
     * **A row lock and not the advisory one, because here there IS a row**: both seats
     * exist from the moment the wedding does (2026-08-22 §2), which is what turns "two
     * people opened the same link" into a lost update. Under `FOR UPDATE` the loser
     * waits, and Postgres re-evaluates the qualification against the committed row
     * afterwards — so it reads the winner's `user_id` rather than the empty seat its
     * own snapshot saw, and is refused with [PartnerAlreadyJoinedException].
     *
     * `@SQLRestriction` supplies `deleted_at is null`, so a released seat is `null`
     * here — an invite pointing at one is dead and answers as if it had never existed.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from WeddingSeat s where s.id = :seatId")
    fun lockSeat(
        @Param("seatId") seatId: Long,
    ): WeddingSeat?

    /**
     * The wedding's unclaimed seats, locked — `#181`'s issue path, where the lock is
     * what makes two simultaneous 재발급 taps end in one live invite instead of two.
     *
     * **A list rather than a single row**, because "exactly one seat is waiting" is a
     * property of today's product rather than of this query: `POST /weddings` fills one
     * of the two, and nothing else can. A `single()` here would turn a state this
     * schema permits into a masked 500 on somebody's 설정 screen; taking the first
     * refuses nothing and invents nothing.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from WeddingSeat s where s.weddingId = :weddingId and s.userId is null order by s.id")
    fun lockWaitingSeats(
        @Param("weddingId") weddingId: Long,
    ): List<WeddingSeat>

    /**
     * Takes this person's "one seat" slot for the rest of the caller's transaction,
     * so that the question above can only be asked and answered once at a time. Two
     * simultaneous `POST /weddings` from one account both found nothing and both
     * created a wedding before this existed — six out of six, in the test that now
     * guards it.
     *
     * **An advisory lock because there is no row to lock.** The rule constrains a
     * person who holds *no* seat yet, and `SELECT ... FOR UPDATE` cannot lock a row
     * that does not exist; `ux_party_wedding_side` cannot help either, being keyed
     * `(wedding_id, side)`, so two seats in two different weddings never collide in
     * it. `ux_party_user` is not a substitute either — an index refuses the second
     * INSERT, it does not stop the second transaction from reading nothing first. The
     * lock is released by COMMIT or ROLLBACK — there is nothing to unlock by hand,
     * and a failed request cannot leak one.
     *
     * **The key is the user id, and this is the application's only advisory lock.** A
     * second kind of lock must namespace both, or a wedding id and a user id of the
     * same value would serialise two unrelated transactions — which costs waiting,
     * never a wrong answer.
     *
     * `select 1 from (...)`: `pg_advisory_xact_lock` returns `void`, and reading a
     * void column back through JPA is a mapping nobody should have to think about.
     */
    @Query(
        value = "select 1 from (select pg_advisory_xact_lock(:userId)) as seat_slot",
        nativeQuery = true,
    )
    fun lockSeatSlotOf(
        @Param("userId") userId: Long,
    ): Int
}
