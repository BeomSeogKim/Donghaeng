package com.donghaeng.wedding

import org.springframework.data.jpa.repository.JpaRepository

/**
 * `ix_membership_user` exists for this one query — it is the index the baseline
 * schema names `user -> membership -> wedding` after.
 */
internal interface MembershipRepository : JpaRepository<Membership, Long> {
    fun existsByWeddingIdAndUserIdAndDeletedAtIsNull(
        weddingId: Long,
        userId: Long,
    ): Boolean
}
