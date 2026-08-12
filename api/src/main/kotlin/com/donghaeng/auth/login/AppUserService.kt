package com.donghaeng.auth.login

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Reads about the signed-in person.
 *
 * It has one method and could have been a repository call in the controller. It
 * is a service because the layering is Controller → Service → Repository
 * (notes/2026-08-07-decision-backend-architecture.md) and this is the first
 * controller in the codebase — whatever shape it takes is the shape fifteen later
 * ones copy, and "it was only one call" is how a controller ends up holding
 * invariants nothing else can see.
 */
@Service
internal class AppUserService(
    private val users: AppUserRepository,
) {
    /**
     * Returns the DTO rather than the entity, deliberately (api/AGENTS.md, API
     * conventions). Mapping in the controller instead would put the read outside
     * this transaction, and with `open-in-view: false` the first domain that has
     * associations — `guest`, not `app_user` — would find that out as a
     * `LazyInitializationException` rather than as a rule.
     */
    @Transactional(readOnly = true)
    fun profile(userId: Long): MeResponse {
        // A resolved session names a row a foreign key guarantees exists, so its
        // absence is a corrupted database rather than a request to answer.
        val user =
            users.findById(userId).orElseThrow {
                IllegalStateException("session resolved to app_user $userId, which does not exist")
            }
        return user.toMeResponse()
    }
}
