package com.donghaeng.wedding

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * `ix_membership_user` exists for the `user_id` lookups here — it is the index the
 * baseline schema names `user -> membership -> wedding` after. It is deliberately
 * NOT unique, which is why [lockMembershipSlotOf] exists.
 */
internal interface MembershipRepository : JpaRepository<Membership, Long> {
    fun existsByWeddingIdAndUserIdAndDeletedAtIsNull(
        weddingId: Long,
        userId: Long,
    ): Boolean

    /**
     * Does this person already belong to a wedding? — 한 사람은 웨딩 하나
     * (`WeddingService.claimSoleMembership`, which is the only caller).
     *
     * **`deleted_at is null` is spelled out even though `@SQLRestriction` adds it**,
     * the stance `WeddingRepository` already takes. Here the condition is the whole
     * question: a person removed from the wedding they were in keeps the row, and if
     * that row counted they could never have a ledger again
     * (notes/2026-08-10-decision-soft-delete.md).
     */
    fun existsByUserIdAndDeletedAtIsNull(userId: Long): Boolean

    /**
     * Takes this person's "one membership" slot for the rest of the caller's
     * transaction, so that the question above can only be asked and answered once at
     * a time. Two simultaneous `POST /weddings` from one account both found nothing
     * and both created a wedding before this existed — six out of six, in the test
     * that now guards it.
     *
     * **An advisory lock because there is no row to lock.** The rule constrains a
     * person who has *no* membership yet, and `SELECT ... FOR UPDATE` cannot lock a
     * row that does not exist; `ux_membership_wedding_user` cannot help either, being
     * keyed `(wedding_id, user_id)`, so two memberships in two different weddings
     * never collide in it. The lock is released by COMMIT or ROLLBACK — there is
     * nothing to unlock by hand, and a failed request cannot leak one.
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
        value = "select 1 from (select pg_advisory_xact_lock(:userId)) as membership_slot",
        nativeQuery = true,
    )
    fun lockMembershipSlotOf(
        @Param("userId") userId: Long,
    ): Int
}
