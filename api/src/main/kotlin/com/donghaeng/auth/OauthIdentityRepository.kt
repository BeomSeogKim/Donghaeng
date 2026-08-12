package com.donghaeng.auth

import org.springframework.data.jpa.repository.JpaRepository

internal interface OauthIdentityRepository : JpaRepository<OauthIdentity, Long> {
    fun findByProviderAndProviderUserId(
        provider: String,
        providerUserId: String,
    ): OauthIdentity?
}
