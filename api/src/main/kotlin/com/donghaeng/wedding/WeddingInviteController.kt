package com.donghaeng.wedding

import com.donghaeng.auth.session.AuthenticatedUser
import com.donghaeng.auth.session.CurrentUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.Parameters
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * 파트너 초대의 두 끝 (`#181`, the backend half of `#9`) — and the two are deliberately
 * one controller, because reading them together is what makes the asymmetry visible:
 * **issuing is wedding-scoped and accepting cannot be.** [preview] joined them on
 * 2026-08-23 (`#214`) on the accepting side, where it inherits every one of that
 * side's properties.
 */
@RestController
class WeddingInviteController internal constructor(
    private val invites: WeddingInviteService,
) {
    /**
     * 초대 링크 발급 / 재발급, from 설정. Wedding-scoped in the ordinary way: the seat
     * walk decides whether this caller may invite anyone into this wedding, so a
     * logged-in stranger gets 404 and never 403
     * (notes/2026-08-10-decision-cross-tenant-status-code.md).
     *
     * **No request body**, and a `consumes` all the same: it is a mapping condition,
     * which is what forces the CORS preflight that stands in for a CSRF token in v1
     * (notes/2026-08-13-decision-static-front-and-content-type-gate.md). Minting a
     * credential is precisely the write a cross-site POST would want.
     *
     * The `{weddingId}` parameter is declared to springdoc by hand — it is in the path
     * template and in no signature, because no handler may take one.
     */
    @Operation(operationId = "issueInvite", summary = "Mint the link that fills the wedding's empty seat, killing any previous one")
    @Parameters(
        Parameter(
            name = "weddingId",
            `in` = ParameterIn.PATH,
            required = true,
            schema = Schema(type = "integer", format = "int64"),
        ),
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "201",
            description = "A live invite. The token is published here and can never be read back.",
        ),
        ApiResponse(
            responseCode = "401",
            description = "No session, or an expired or revoked one.",
            content = [Content(schema = Schema(implementation = ProblemDetail::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "No such wedding — which is also the answer when it exists and the caller holds no seat in it.",
            content = [Content(schema = Schema(implementation = ProblemDetail::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "Both seats are taken, so there is nobody left to invite.",
            content = [Content(schema = Schema(implementation = ProblemDetail::class))],
        ),
    )
    @PostMapping("/weddings/{weddingId}/invite", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun issue(
        @CurrentWedding wedding: WeddingScope,
    ): IssuedInviteResponse = invites.issue(wedding)

    /**
     * 초대 수락 화면의 미리보기 (`#214`) — 결혼식 이름, 예식일, 초대한 사람, and nothing
     * else. **A fourth scopeless endpoint**, named in `ScopelessWeddingEndpointTest`
     * with its reason; the token stands in for the scope exactly as it does for
     * [join], because the caller holds no seat yet.
     *
     * **A POST that reads nothing back into the database and writes nothing.** The
     * method is the token's doing rather than the operation's: a token may not travel
     * in a path or a query string, so it travels in a body, and a body means POST
     * (notes/2026-08-22-decision-the-invite-link.md §2). The `consumes` that comes with
     * it forces the CORS preflight that stands in for a CSRF token in v1.
     *
     * **It is authenticated, like [join] and for the same two reasons.** Under
     * `permitAll` the session is the only thing in front of an endpoint that takes no
     * scope, and this endpoint answers with somebody else's wedding — so an anonymous
     * request is refused before the body is read. It also keeps the whole invite flow
     * one flow: the person signs in first, and the screen shows them what they are
     * about to accept.
     */
    @Operation(operationId = "previewInvite", summary = "What an invite link opens — before taking the seat")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "The wedding the token would join, named but not identified."),
        ApiResponse(
            responseCode = "401",
            description = "No session, or an expired or revoked one.",
            content = [Content(schema = Schema(implementation = ProblemDetail::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "The token is not usable — unknown, wrong, already spent, replaced by a reissue, or expired.",
            content = [Content(schema = Schema(implementation = ProblemDetail::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "The caller already belongs to a wedding, or the seat has been taken.",
            content = [Content(schema = Schema(implementation = ProblemDetail::class))],
        ),
    )
    @PostMapping("/weddings/join/preview", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun preview(
        @CurrentUser caller: AuthenticatedUser,
        @Valid @RequestBody request: InvitePreviewRequest,
    ): InvitePreviewResponse = invites.preview(caller.id, request)

    /**
     * 초대 수락 — **not scoped to a wedding**, like `POST /weddings`, `GET /weddings`
     * and [preview], and the first of them that could not be: the caller holds no seat
     * yet, so `user → seat → wedding` has nothing to resolve.
     * The exemption is written down in `ScopelessWeddingEndpointTest` rather than
     * inferred from this path lacking a `{weddingId}`.
     *
     * **What stands in for the scope is the token, and it arrives in the BODY.** Never
     * in the path: a token in a path is recorded in the access log and reflected in an
     * error document's `instance`, which is the whole of `#69`'s complaint
     * (notes/2026-08-22-decision-the-invite-link.md §2). The frontend reads it from the
     * URL fragment, which no browser sends anywhere.
     *
     * [AuthenticatedUser] is the first parameter so that an anonymous request is refused
     * before its body is read — one answer, rather than one that depends on what was
     * sent. It is also what keeps a token from reaching a flow with no account to seat.
     */
    @Operation(operationId = "joinWedding", summary = "Take the wedding's empty seat, using an invite token")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "The caller now holds the seat; the wedding they joined is the body."),
        ApiResponse(
            responseCode = "400",
            description = "A blank or over-long name, or a body that could not be read.",
            content = [Content(schema = Schema(implementation = ProblemDetail::class))],
        ),
        ApiResponse(
            responseCode = "401",
            description = "No session, or an expired or revoked one.",
            content = [Content(schema = Schema(implementation = ProblemDetail::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "The token is not usable — unknown, wrong, already spent, replaced by a reissue, or expired.",
            content = [Content(schema = Schema(implementation = ProblemDetail::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "The caller already belongs to a wedding, or the seat was taken while they were deciding.",
            content = [Content(schema = Schema(implementation = ProblemDetail::class))],
        ),
    )
    @PostMapping("/weddings/join", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun join(
        @CurrentUser caller: AuthenticatedUser,
        @Valid @RequestBody request: JoinWeddingRequest,
    ): WeddingResponse = invites.accept(caller.id, request)
}
