package com.donghaeng.auth

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
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
    private val sessions: SessionService,
    private val cookies: SessionCookies,
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

    /**
     * Ends this device's session (`#90`).
     *
     * **POST, not GET**, and that is not a REST preference: v1's CSRF answer is
     * `SameSite=Lax` plus no state-changing GET (`SecurityConfig`), and Lax admits
     * the cookie on top-level GET navigation — so a logout reachable by GET is an
     * `<img src>` away from signing the couple out at an attacker's choosing.
     * Under POST the cookie is withheld cross-site and the request cannot revoke
     * anything.
     *
     * **It takes no [AuthenticatedUser], deliberately**, which makes it one of the
     * handlers `#5`'s fail-closed mechanism has to be able to call PUBLIC on
     * purpose rather than treat as an omission.
     *
     * **Always 204, whatever it finds.** No cookie, an unparseable one, several at
     * once, a session that expired, one already revoked, one revoked by another
     * device — every one of them means "you are not logged in on this device",
     * which is precisely what the caller asked for. A logout that answers 401 is a logout the client
     * has to write error handling for, and error handling for "you are already
     * logged out" is how a sign-out button ends up leaving people signed in. It is
     * idempotent for the same reason.
     *
     * The response also **clears the cookie**, and both halves are load-bearing:
     * revoking the row is what makes the token dead everywhere, while expiring the
     * cookie is what stops the browser from presenting a dead token on every
     * subsequent request. Doing only the first leaves a client that looks logged
     * in until something 401s; doing only the second is not logout at all.
     */
    @Operation(summary = "End the session on this device")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "The session is over, whether or not there was one."),
    )
    @PostMapping("/auth/logout")
    fun logout(request: HttpServletRequest): ResponseEntity<Void> {
        // EVERY token, not the single unambiguous one. `SessionTokens.of` answers
        // `null` when the browser presents more than one `DH_SESSION`, and on that
        // path this used to revoke nothing and then clear the cookie — deleting
        // only the host-only one, since a `Set-Cookie` without a `Domain` cannot
        // touch a sibling's. A planted cookie was left alone in the jar and still
        // valid, so the next request resolved cleanly AS THE ATTACKER: the sign-out
        // gesture completed the takeover the ambiguity rule exists to prevent
        // (notes/2026-08-12-decision-session-cookie-ambiguity.md).
        SessionTokens.all(request).forEach(sessions::revoke)
        return ResponseEntity
            .noContent()
            .header(HttpHeaders.SET_COOKIE, cookies.expire().toString())
            .build()
    }
}
