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
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class HeadcountController internal constructor(
    private val headcounts: HeadcountService,
) {
    /**
     * 인원수 (`#151`, the backend half of `#17`), specified in `docs/api-spec.md`.
     *
     * **A second endpoint beside the ledger rather than a member of it.** 원장과
     * 인원수는 한 화면이지만 한 응답은 아니다: the list is a read and carries no
     * aggregate (notes/2026-08-20-decision-the-ledger-read-and-its-filters.md), so
     * the screen opens both and a *mutation* answers with the recomputed number
     * itself.
     *
     * **The operation id is spelled out** for the reason `GuestController.create`
     * spells its own out: `WeddingController.read` already owns `read`, and
     * springdoc would hand `web/` a `read_1` whose number moves with registration
     * order.
     *
     * The `{weddingId}` parameter is declared to springdoc by hand — it is in the
     * path template and in no signature, because no handler may take one.
     */
    @Operation(operationId = "readHeadcount", summary = "The wedding's 식대 인원, and its 보증인원 when one is set")
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
                "The recomputed number. `mealHeadcount` is 0 for a ledger with nobody in it, and " +
                    "`guaranteedHeadcount` is absent until the couple has agreed one with their venue.",
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
    @GetMapping("/weddings/{weddingId}/headcount")
    fun read(
        @CurrentWedding wedding: WeddingScope,
    ): HeadcountResponse = headcounts.of(wedding)
}
