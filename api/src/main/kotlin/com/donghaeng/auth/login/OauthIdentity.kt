package com.donghaeng.auth.login

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * "This provider's subject id is this [AppUser]." The primary key of a returning
 * login: the same person's second Google login is recognised here, and the
 * verified-email merge is only consulted when this lookup finds nothing.
 *
 * [provider] is a varchar rather than an enum because a fourth provider must be a
 * deploy and not an `ALTER TYPE` (api/AGENTS.md, Domain mechanisms). v1 writes
 * only `GOOGLE`; `KAKAO` and `NAVER` arrive with #89.
 */
@Entity
@Table(name = "oauth_identity")
internal class OauthIdentity(
    @Column(name = "user_id", nullable = false)
    val userId: Long,
    @Column(name = "provider", nullable = false, length = 20)
    val provider: String,
    @Column(name = "provider_user_id", nullable = false, length = 255)
    val providerUserId: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long = 0,
)
