package com.donghaeng.auth.account

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

internal interface AppUserRepository : JpaRepository<AppUser, Long> {
    /**
     * The account-merge lookup, and the expression is the contract (#82,
     * notes/2026-08-11-decision-baseline-schema-calls.md §A).
     *
     * It is written natively because it must be **the same expression
     * `ux_app_user_email` is built on**, character for character. JPQL's `lower()`
     * renders as a bare `lower(email)`: a different expression, so the planner
     * cannot use the index, and — the part that actually bites — a different
     * FUNCTION, because under this database's own ctype `lower()` is not
     * injective. An index that forbids a duplicate does not make the lookup find
     * it; a lookup that misses takes the create-account branch and dies on the
     * unique violation, turning a silent account split into a **500 on login**.
     *
     * [mergeKey] must already be ASCII-lowercased — the provider profiles are the
     * only callers and the only normalisers. Kotlin's `String.lowercase()` is full
     * Unicode case mapping and is NOT `lower(... collate "C")`.
     */
    @Query(
        value = """select * from app_user where lower(email collate "C") = :mergeKey""",
        nativeQuery = true,
    )
    fun findByMergeKey(
        @Param("mergeKey") mergeKey: String,
    ): AppUser?
}
