package com.donghaeng.auth

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Duration
import java.time.Instant

/**
 * One issued session. The row the cookie's `<selector>.<verifier>` resolves to;
 * see `V2__user_session.sql` for why the token has two halves.
 *
 * Nothing here can be replayed if the table leaks: [selector] carries no
 * authority on its own and [verifierHash] is a hash.
 */
@Entity
@Table(name = "user_session")
internal class UserSession(
    @Column(name = "selector", nullable = false, length = 32)
    val selector: String,
    @Column(name = "verifier_hash", nullable = false, length = 64)
    val verifierHash: String,
    @Column(name = "user_id", nullable = false)
    val userId: Long,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "last_seen_at", nullable = false)
    var lastSeenAt: Instant,
    @Column(name = "revoked_at")
    var revokedAt: Instant? = null,
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long = 0,
) {
    /**
     * Both expiries, and they are two different questions: [idle] is measured from
     * the last request, [absolute] from the moment the session was issued. A
     * configuration file can express the first and cannot express the second, so
     * this is where the second lives (#5's handover comment, carried to #37 by
     * notes/2026-08-12-decision-login-slice-by-provider.md).
     */
    fun isUsableAt(
        now: Instant,
        idle: Duration,
        absolute: Duration,
    ): Boolean =
        revokedAt == null &&
            now.isBefore(lastSeenAt.plus(idle)) &&
            now.isBefore(createdAt.plus(absolute))
}

internal interface UserSessionRepository : JpaRepository<UserSession, Long> {
    /**
     * By the selector alone — deliberately. The verifier is compared afterwards,
     * in constant time, by [SessionService.resolve]; asking the database to match
     * it would put the comparison somewhere no test can watch it fail
     * (notes/2026-08-12-decision-session-token-shape.md).
     */
    fun findBySelector(selector: String): UserSession?
}
