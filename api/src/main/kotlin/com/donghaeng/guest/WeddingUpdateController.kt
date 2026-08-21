package com.donghaeng.guest

import com.donghaeng.wedding.CurrentWedding
import com.donghaeng.wedding.UpdateWeddingRequest
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
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class WeddingUpdateController internal constructor(
    private val weddings: WeddingUpdateService,
) {
    /**
     * 웨딩 정보 수정 (`#173`, the backend half of `#8`), specified in
     * `docs/api-spec.md` — **and the endpoint 보증인원 enters the product through.**
     * Until it existed the column was NULL on every row, so the comparison the
     * headcount screen is built around could not render for anybody.
     *
     * **PATCH, not PUT**, and the difference is not spelling: a member the caller
     * omitted is not written at all, so the partner editing 예식일 does not
     * blind-write a 보증인원 their form loaded five minutes ago
     * (notes/2026-08-22-decision-partial-update-shape.md).
     *
     * It lives in `guest/` although it writes a wedding, because the response
     * carries the recomputed 인원수 — see [WeddingUpdateService], which is where that
     * is argued.
     *
     * [WeddingScope] is the first parameter, as on every wedding-scoped handler:
     * resolution runs before the body is read, so an anonymous request is 401 and a
     * stranger's is 404, neither of them a 400 that would list this endpoint's fields
     * to someone with no claim on the wedding.
     *
     * The `{weddingId}` parameter is declared to springdoc by hand — it is in the
     * path template and in no signature, because no handler may take one.
     */
    @Operation(operationId = "updateWedding", summary = "Change 예식일 or 보증인원, and answer with the recomputed 인원수")
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
            description =
                "The wedding as it now stands, and the recomputed 인원수. An empty body is a legal no-op: " +
                    "it changes nothing and answers the current state.",
        ),
        ApiResponse(
            responseCode = "400",
            description =
                "A 보증인원 below 1, an unstorable 예식일, a `weddingDate` sent as `null` — it cannot be cleared — " +
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
                "No such wedding — which is also the answer when it exists and the caller holds no seat in it, " +
                    "and when it has been deleted.",
            content = [Content(schema = Schema(implementation = ProblemDetail::class))],
        ),
    )
    @PatchMapping("/weddings/{weddingId}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun update(
        @CurrentWedding wedding: WeddingScope,
        @Valid @RequestBody request: UpdateWeddingRequest,
    ): WeddingMutationResponse = weddings.update(wedding, request)
}
