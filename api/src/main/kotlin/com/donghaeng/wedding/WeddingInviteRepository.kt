package com.donghaeng.wedding

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

/**
 * `ux_wedding_invite_selector` serves [findBySelector], which is the lookup every
 * acceptance runs — including every guess — and `ux_wedding_invite_live` is the last
 * word on "at most one live invite per seat", which the two writes below are the
 * ordinary way of keeping.
 */
internal interface WeddingInviteRepository : JpaRepository<WeddingInvite, Long> {
    /**
     * By the PUBLIC half of the token, never by the whole of it: the row is found by
     * selector and the verifier is then compared as a hash, in constant time, by
     * [InviteToken.matches]. That split is what gives the comparison somewhere to live
     * where deleting it turns a test red, which a lookup keyed on the hash alone does
     * not (`V2__user_session.sql`'s header makes the same argument for sessions).
     */
    fun findBySelector(selector: String): WeddingInvite?

    /**
     * 재발급 killing what came before it, in the transaction that mints the
     * replacement (notes/2026-08-22-decision-the-invite-link.md §1).
     *
     * A bulk UPDATE rather than a load-and-mutate because it must run BEFORE the
     * insert that takes the seat's live slot — Spring Data executes a `@Modifying`
     * query immediately, whereas a dirty entity would flush whenever Hibernate felt
     * like it, and the wrong order is a unique-violation on `ux_wedding_invite_live`
     * rather than a reissue.
     *
     * Returns how many it killed, which is 0 or 1 and is not asserted on: a seat with
     * no live invite is the ordinary state of a first issue.
     */
    @Modifying
    @Query("update WeddingInvite i set i.revokedAt = :now where i.seatId = :seatId and i.acceptedAt is null and i.revokedAt is null")
    fun revokeLiveInviteFor(
        @Param("seatId") seatId: Long,
        @Param("now") now: Instant,
    ): Int

    /**
     * SINGLE USE, spelled as a conditional UPDATE so that the condition is checked by
     * the database at the moment of the write rather than by the application a moment
     * earlier. A rowcount of 0 means the token was spent or revoked in between, and the
     * caller answers exactly what it answers for a token that was never ours.
     *
     * The seat's row lock is what actually serialises two people opening the same link
     * ([WeddingSeatRepository.lockSeat]); this is the backstop that does not depend on
     * the lock being taken.
     */
    @Modifying
    @Query(
        "update WeddingInvite i set i.acceptedAt = :now, i.acceptedBy = :userId " +
            "where i.id = :id and i.acceptedAt is null and i.revokedAt is null",
    )
    fun consume(
        @Param("id") id: Long,
        @Param("userId") userId: Long,
        @Param("now") now: Instant,
    ): Int
}
