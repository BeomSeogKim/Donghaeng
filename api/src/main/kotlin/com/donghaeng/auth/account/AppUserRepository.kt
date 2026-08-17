package com.donghaeng.auth.account

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

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

    /**
     * Writes a display name the provider has changed since we last saw this person,
     * and writes NOTHING when it has not (#94, [ProfileRefreshService]).
     *
     * The predicate is what makes that true, and it is `is distinct from` rather
     * than `<>` for the row that has no name yet: `name <> 'x'` is NULL there, so
     * `<>` would leave a nameless account nameless forever — which is the same
     * "a returning user's profile never catches up" this issue is about, one column
     * over.
     *
     * A statement rather than a read-then-save: the read exists only to decide
     * whether to write, so Postgres may as well decide, and a row that matches
     * nothing is not locked and no new tuple is written. Two logins for one person
     * racing here settle on one of the two names rather than on a lost update.
     *
     * Which columns it may touch is `AppUserWriteScopeTest`'s to say, not this
     * comment's.
     */
    @Modifying
    @Query(
        value = """
            update app_user
               set name = :name, updated_at = :now
             where id = :id and name is distinct from :name
            """,
        nativeQuery = true,
    )
    fun renameIfChanged(
        @Param("id") id: Long,
        @Param("name") name: String,
        @Param("now") now: Instant,
    ): Int
}
