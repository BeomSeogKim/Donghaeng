package com.donghaeng.wedding

import com.donghaeng.auth.session.AuthenticatedUser
import com.donghaeng.auth.session.CurrentUser
import io.swagger.v3.oas.annotations.Operation
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
}
