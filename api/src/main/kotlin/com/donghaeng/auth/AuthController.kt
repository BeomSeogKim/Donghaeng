package com.donghaeng.auth

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The signed-in person. `email` and `name` are whatever Google told us and may
 * both be absent — `email` is only ever present when a provider asserted it as
 * verified (notes/2026-08-11-decision-baseline-schema-calls.md §A).
 */
internal data class MeResponse(
    val id: Long,
    val email: String?,
    val name: String?,
)

@RestController
internal class AuthController(
    private val users: AppUserRepository,
) {
    /**
     * "Am I logged in, and as whom?" — the frontend's first call on every load,
     * and the only observation point v1 has for a session actually resolving.
     */
    @Operation(summary = "The session's own app_user")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "The session resolved."),
        // The schema is stated because springdoc otherwise infers the METHOD's
        // return type for every declared response, and would tell `web/` that a
        // 401 carries a MeResponse. Errors are problem documents, always
        // (docs/api-spec.md); #66 is the general version of this gap.
        ApiResponse(
            responseCode = "401",
            description = "No session, or an expired or revoked one.",
            content = [Content(schema = Schema(implementation = ProblemDetail::class))],
        ),
    )
    @GetMapping("/auth/me")
    fun me(
        @CurrentUser caller: AuthenticatedUser,
    ): MeResponse {
        // A resolved session names a row a foreign key guarantees exists, so its
        // absence is a corrupted database rather than a request to answer.
        val user =
            users.findById(caller.id).orElseThrow {
                IllegalStateException("session resolved to app_user ${caller.id}, which does not exist")
            }
        return MeResponse(id = user.id, email = user.email, name = user.name)
    }
}
