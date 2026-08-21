package com.donghaeng.wedding

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

/**
 * `ux_subscription_live` is what every query here is keyed on: the live term is the
 * one with no `ended_at`, and 웨딩당 활성 구독 1건 makes that at most one row per
 * wedding no matter how requests interleave
 * (notes/2026-08-22-decision-entitlement-belongs-to-the-wedding.md §4).
 */
internal interface WeddingSubscriptionRepository : JpaRepository<WeddingSubscription, Long> {
    /**
     * The live term of one wedding, or `null`.
     *
     * `null` is only reachable for a wedding written outside this application:
     * [WeddingService.create] opens a FREE term in the same transaction as the
     * wedding, so a live term is part of what a wedding IS.
     */
    fun findByWeddingIdAndEndedAtIsNull(weddingId: Long): WeddingSubscription?

    /**
     * Ends the live term, and hands back how many rows that was — which is `1` for
     * every wedding this application created and `0` for a handover that lost a race
     * to another one.
     *
     * **A bulk UPDATE rather than a load-mutate-save**, because it is also the lock:
     * `where ended_at is null` takes the live row's write lock, so a second handover
     * waits here rather than reading the same live term and racing to insert beside
     * it. It is the only reason this is not two lines in the service.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update WeddingSubscription t
        set t.endedAt = :at, t.updatedAt = :at
        where t.weddingId = :weddingId and t.endedAt is null
        """,
    )
    fun endLiveTerm(
        @Param("weddingId") weddingId: Long,
        @Param("at") at: Instant,
    ): Int
}
