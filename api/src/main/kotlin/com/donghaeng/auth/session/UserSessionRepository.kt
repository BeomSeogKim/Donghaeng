package com.donghaeng.auth.session

import org.springframework.data.jpa.repository.JpaRepository

internal interface UserSessionRepository : JpaRepository<UserSession, Long> {
    /**
     * By the selector alone — deliberately. The verifier is compared afterwards,
     * in constant time, by [SessionService.resolve]; asking the database to match
     * it would put the comparison somewhere no test can watch it fail
     * (notes/2026-08-12-decision-session-token-shape.md).
     */
    fun findBySelector(selector: String): UserSession?
}
