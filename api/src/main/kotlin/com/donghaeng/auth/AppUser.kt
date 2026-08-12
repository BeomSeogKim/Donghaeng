package com.donghaeng.auth

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

/**
 * A person. Outside every wedding — one person may belong to several, and the
 * session never knows which (`V1__baseline_schema.sql`, Identity).
 *
 * [email] is the ACCOUNT MERGE KEY and is written only when the provider asserts
 * the address as verified (notes/2026-08-11-decision-baseline-schema-calls.md §A).
 * Everything that decides whether an address may be written lives in
 * [GoogleProfile.mergeKey]; this class only holds the result. Three CHECK
 * constraints and one unique index stand behind it, and a value that fails any of
 * them arrives here as a 500 on login rather than as a wrong row — which is the
 * arrangement the schema comments describe as "making forgetting it loud".
 *
 * Column lengths repeat the migration's on purpose. `ddl-auto: validate` compares
 * JDBC type codes only, so a `varchar(20)` typed where the file says 255 passes
 * validation and fails at INSERT (api/AGENTS.md, Schema ownership) — these
 * declarations are the only other place the sizes are written down.
 */
@Entity
@Table(name = "app_user")
internal class AppUser(
    @Column(name = "email", length = 255)
    var email: String? = null,
    @Column(name = "email_verified_by", length = 20)
    var emailVerifiedBy: String? = null,
    @Column(name = "name", length = 100)
    var name: String? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long = 0,
)

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
