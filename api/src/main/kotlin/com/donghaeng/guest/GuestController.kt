package com.donghaeng.guest

import com.donghaeng.wedding.CurrentWedding
import com.donghaeng.wedding.WeddingScope
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
}
