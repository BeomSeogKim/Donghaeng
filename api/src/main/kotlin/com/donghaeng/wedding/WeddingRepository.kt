package com.donghaeng.wedding

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * **`deleted_at is null` is spelled out here even though `@SQLRestriction` on
 * [Wedding] already adds it.** The ambient filter is what made a deleted wedding
 * with a live membership resolvable in the first place — nothing said the walk had
 * to look at `wedding.deleted_at`, so nothing did — and a condition that carries the
 * gate belongs where the query is read, not one file away
 * (notes/2026-08-10-decision-soft-delete.md).
 */
internal interface WeddingRepository : JpaRepository<Wedding, Long> {
    fun existsByIdAndDeletedAtIsNull(id: Long): Boolean

    fun findByIdAndDeletedAtIsNull(id: Long): Wedding?

    /**
     * The weddings a person is a live member of — `#132`, and **the one wedding read
     * with no `{weddingId}` to resolve**, so the join here IS the scope. It returns
     * exactly the rows [WeddingService.scopeFor] would accept one at a time, and the
     * two conditions are the same two, for the same reasons: a revoked membership
     * must not keep a ledger, and a soft-deleted wedding must not stay readable to
     * everyone who was ever in it.
     *
     * A join rather than two queries because there is no association to traverse:
     * [Membership] holds ids, not a `@ManyToOne`, so `wedding/` can carry its own
     * rows without mapping `auth/`'s.
     *
     * **Newest first, which as of 2026-08-21 sorts nothing.** `ux_membership_user`
     * makes a second live membership unrepresentable, so this query can return at
     * most one row — and the contract test that exercised the order was deleted with
     * the index, because the state it needed is a row no database of ours can hold
     * (`WeddingListContractTest`). The clause stays: sorting one row costs nothing,
     * it is what `docs/api-spec.md` publishes, and it is the correct default the day
     * a person may hold several weddings again. Do not read it as live behaviour.
     */
    @Query(
        """
        select w from Wedding w, Membership m
        where m.weddingId = w.id
          and m.userId = :userId
          and m.deletedAt is null
          and w.deletedAt is null
        order by w.createdAt desc, w.id desc
        """,
    )
    fun findAllLiveForMember(
        @Param("userId") userId: Long,
    ): List<Wedding>
}
