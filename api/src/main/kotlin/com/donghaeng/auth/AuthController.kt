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
 * Public, and its constructor is not: the architecture record makes the controller
 * the one public type in a domain package, while everything it depends on stays
 * `internal`. Kotlin will not let a public constructor name an internal type, so
 * the visibility split lands on the constructor — which Spring calls reflectively
 * and does not care about.
 */
@RestController
class AuthController internal constructor(
    private val users: AppUserService,
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
    ): MeResponse = users.profile(caller.id)
}
