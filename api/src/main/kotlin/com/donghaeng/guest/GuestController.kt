package com.donghaeng.guest

import com.donghaeng.wedding.CurrentWedding
import com.donghaeng.wedding.WeddingScope
import com.donghaeng.wedding.WeddingSide
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.Parameters
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
class GuestController internal constructor(
    private val guests: GuestService,
) {
    /**
     * 하객 추가 (`#11`), specified in `docs/api-spec.md`.
     *
     * **[WeddingScope] is the first parameter, and that order is the authorization**:
     * resolution runs before the body is read, so an anonymous request is 401 rather
     * than a 400 that lists this endpoint's fields to someone with no claim on the
     * wedding (notes/2026-08-10-decision-auth-gate-and-sequence.md).
     *
     * **The operation id is spelled out** because springdoc would otherwise derive it
     * from the method name, and `WeddingController.create` already owns `create`:
     * this handler would reach `web/` as `create_1`, a name assigned in registration
     * order that a third `create` handler renumbers.
     *
     * The `{weddingId}` parameter is declared to springdoc by hand: it is in the path
     * template and in no signature, because no handler may take one (swept by
     * `ResolvedPrincipalTest`).
     */
    @Operation(operationId = "createGuest", summary = "Add a guest to the ledger")
    @Parameters(
        Parameter(
            name = "weddingId",
            `in` = ParameterIn.PATH,
            required = true,
            schema = Schema(type = "integer", format = "int64"),
        ),
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "The guest was added to this wedding's ledger."),
        ApiResponse(
            responseCode = "400",
            description =
                "A blank or over-long name, a party size below 1, an over-long optional field, " +
                    "or a body that could not be read.",
            content = [Content(schema = Schema(implementation = ProblemDetail::class))],
        ),
        ApiResponse(
            responseCode = "401",
            description = "No session, or an expired or revoked one.",
            content = [Content(schema = Schema(implementation = ProblemDetail::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description =
                "No such wedding — which is also the answer when it exists and the caller is not a member of it, " +
                    "and when it has been deleted.",
            content = [Content(schema = Schema(implementation = ProblemDetail::class))],
        ),
    )
    @PostMapping("/weddings/{weddingId}/guests", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @CurrentWedding wedding: WeddingScope,
        @Valid @RequestBody request: CreateGuestRequest,
    ): GuestMutationResponse = guests.create(wedding, request)

    /**
     * 원장 목록 (`#147`, the backend half of `#15`) — the screen every other v1 screen
     * opens on top of (notes/2026-08-07-design-screens-and-flow.md).
     *
     * **Two filters, both optional, and no third one.** 그룹 is an aggregation axis
     * and never a way to narrow the list, so it is absent by decision rather than by
     * omission; the same goes for a page, which `docs/api-spec.md` argues out in the
     * open because `web/` builds — or does not build — infinite scroll from it.
     *
     * The enums are the seam: `web/` generates its filter chips from these two value
     * sets, so a value spelled here is a value it will render, and a value outside
     * them is a 400 rather than a silently ignored parameter.
     *
     * [WeddingScope] comes first, as everywhere: resolution runs before a parameter
     * is bound, so an anonymous request is 401 whatever it asks for and a stranger's
     * request is 404 before any filter is parsed.
     */
    @Operation(operationId = "listGuests", summary = "The wedding's ledger, filtered by side and attendance")
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
            responseCode = "200",
            description = "The whole ledger, oldest first — an empty array when the couple has entered nobody yet.",
        ),
        ApiResponse(
            responseCode = "400",
            description = "A `side` or `attendance` value outside its set.",
            content = [Content(schema = Schema(implementation = ProblemDetail::class))],
        ),
        ApiResponse(
            responseCode = "401",
            description = "No session, or an expired or revoked one.",
            content = [Content(schema = Schema(implementation = ProblemDetail::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description =
                "No such wedding — which is also the answer when it exists and the caller is not a member of it, " +
                    "and when it has been deleted.",
            content = [Content(schema = Schema(implementation = ProblemDetail::class))],
        ),
    )
    @GetMapping("/weddings/{weddingId}/guests")
    fun list(
        @CurrentWedding wedding: WeddingScope,
        request: HttpServletRequest,
        @Parameter(description = "신랑측 or 신부측. Sent at most once; omitted or empty, both sides")
        @RequestParam(required = false) side: WeddingSide?,
        @Parameter(description = "참석 or 불참. Sent at most once; omitted or empty, both")
        @RequestParam(required = false) attendance: AttendanceFilter?,
    ): List<GuestResponse> {
        refuseRepeated(request, SIDE, ATTENDANCE)
        return guests.list(wedding, side, attendance)
    }

    /**
     * The raw query is read here because a bound parameter cannot see the difference
     * that matters: Spring resolves `?side=GROOM&side=BRIDE` into the FIRST value and
     * the handler is handed something indistinguishable from a caller who sent one.
     * [RepeatedFilterException] carries why that may not be answered 200.
     *
     * Declared after [CurrentWedding] like every other request-supplied parameter, so
     * a stranger's request is refused by the resolver before this reads anything they
     * sent.
     */
    private fun refuseRepeated(
        request: HttpServletRequest,
        vararg filters: String,
    ) {
        filters
            .firstOrNull { (request.getParameterValues(it)?.size ?: 0) > 1 }
            ?.let { throw RepeatedFilterException(it) }
    }

    private companion object {
        private const val SIDE = "side"
        private const val ATTENDANCE = "attendance"
    }
}
