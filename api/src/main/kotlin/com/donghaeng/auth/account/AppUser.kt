package com.donghaeng.auth.account

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * A person. Outside every wedding — they belong to at most one and the session
 * never knows which (`V1__baseline_schema.sql`, Identity).
 *
 * [email] is the ACCOUNT MERGE KEY and is written only when the provider asserts
 * the address as verified (notes/2026-08-11-decision-baseline-schema-calls.md §A).
 * Everything that decides whether an address may be written lives in the provider
 * mapper — [com.donghaeng.auth.oauth.GoogleProfile.mergeKey] today; this class
 * only holds the result. Three CHECK constraints and one unique index stand
 * behind it, and a value that fails any of them arrives here as a 500 on login
 * rather than as a wrong row — which is the arrangement the schema comments
 * describe as "making forgetting it loud".
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
