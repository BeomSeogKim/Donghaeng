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
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
class WeddingController internal constructor(
    private val weddings: WeddingService,
) {
    /**
     * 웨딩 만들기 (`#7`), and **the one endpoint in the product not scoped to a
     * wedding**: it takes a caller and no `CurrentWedding`, because this is where a
     * person's first membership comes from and there is nothing to resolve until it
     * has run (notes/2026-08-10-decision-auth-gate-and-sequence.md).
     *
     * [AuthenticatedUser] is the first parameter so that an anonymous request is
     * refused before its body is read — one answer, rather than one that tells the
     * caller which fields exist. `CurrentUserParameterTest` sweeps that rule.
     */
    @Operation(summary = "Create a wedding and the creator's membership in it")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "The wedding and the caller's membership were created."),
        ApiResponse(
            responseCode = "400",
            description = "A blank or over-long name, an unstorable date, or a body that could not be read.",
            content = [Content(schema = Schema(implementation = ProblemDetail::class))],
        ),
        ApiResponse(
            responseCode = "401",
            description = "No session, or an expired or revoked one.",
            content = [Content(schema = Schema(implementation = ProblemDetail::class))],
        ),
    )
    @PostMapping("/weddings", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @CurrentUser caller: AuthenticatedUser,
        @Valid @RequestBody request: CreateWeddingRequest,
    ): WeddingResponse = weddings.create(caller.id, request)

    /**
     * The first wedding-scoped endpoint in the product (`#5`), and the shape the
     * other fifteen copy: **the wedding arrives resolved, or the request never gets
     * here.**
     *
     * There is no `@PathVariable weddingId` and there may never be one on any
     * handler. `{weddingId}` is a value the caller chose; what makes it theirs is
     * their membership, and [CurrentWedding] resolution is the only thing that
     * checks it (notes/2026-08-10-decision-auth-gate-and-sequence.md). The path
     * variable is therefore read by the resolver, which is also why an id that is
     * not a number answers 404 rather than 400 — one answer for every id the caller
     * may not have.
     *
     * The `{weddingId}` parameter is declared to springdoc by hand for the same
     * reason: it is in the path template and in no signature, and `web/` generates
     * its client from that document.
     */
    @Operation(summary = "The wedding, for a caller who is a member of it")
    @Parameters(
        Parameter(
            name = "weddingId",
            `in` = ParameterIn.PATH,
            required = true,
            schema = Schema(type = "integer", format = "int64"),
        ),
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "The caller is a member of this wedding."),
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
    @GetMapping("/weddings/{weddingId}")
    fun read(
        @CurrentWedding wedding: WeddingScope,
    ): WeddingResponse = weddings.read(wedding.id)
}
