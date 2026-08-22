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
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
class WeddingController internal constructor(
    private val weddings: WeddingService,
) {
    /**
     * 웨딩 만들기 (`#7`), and one of the two endpoints in the product not scoped to a
     * wedding ([list] is the other, `#132`): it takes a caller and no
     * `CurrentWedding`, because this is where a person's first seat comes from and
     * there is nothing to resolve until it has run
     * (notes/2026-08-10-decision-auth-gate-and-sequence.md). Both are named in
     * `ScopelessWeddingEndpointTest`, and a third is a design change rather than a
     * line in that list.
     *
     * **It is also where 한 사람은 웨딩 하나 is enforced** (`#158`): a caller who already
     * belongs to a wedding is refused with 409, not handed a second ledger that would
     * make their first one unreachable.
     *
     * [AuthenticatedUser] is the first parameter so that an anonymous request is
     * refused before its body is read — one answer, rather than one that tells the
     * caller which fields exist. `CurrentUserParameterTest` sweeps that rule.
     */
    @Operation(summary = "Create a wedding, both of its seats and its free subscription term")
    @ApiResponses(
        ApiResponse(
            responseCode = "201",
            description = "The wedding, the caller's seat, their partner's empty seat and a FREE term were created.",
        ),
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
        ApiResponse(
            responseCode = "409",
            description = "The caller already belongs to a wedding; a person belongs to exactly one.",
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
     * 내 웨딩 목록 (`#132`), and **the only wedding READ that takes no `WeddingScope`**
     * — it is what a client calls before it has an id, so there is no id to resolve.
     * `#124` branches its 최초 1회 screen on whether this is empty, and `#15` reloads
     * the ledger from it after a refresh loses the id.
     *
     * The exemption is deliberate and it is written down, not inferred from the path
     * happening to lack `{weddingId}`: `ScopelessWeddingEndpointTest` names this
     * handler and [create] and refuses every other handler under `/weddings` that
     * declares no [WeddingScope], so a sixteenth endpoint cannot inherit the property
     * by copying this signature.
     *
     * **What stands in for the scope is the seat join** in
     * [WeddingRepository.findAllLiveForMember] — it can only return rows the resolver
     * would have accepted — plus [AuthenticatedUser], which under `permitAll` is the
     * only thing between this endpoint and an anonymous one.
     */
    @Operation(summary = "The weddings the caller holds a seat in, newest first")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "The caller's weddings — an empty array when they have none."),
        ApiResponse(
            responseCode = "401",
            description = "No session, or an expired or revoked one.",
            content = [Content(schema = Schema(implementation = ProblemDetail::class))],
        ),
    )
    @GetMapping("/weddings")
    fun list(
        @CurrentUser caller: AuthenticatedUser,
    ): List<WeddingResponse> = weddings.list(caller.id)

    /**
     * The first wedding-scoped endpoint in the product (`#5`), and the shape the
     * other fifteen copy: **the wedding arrives resolved, or the request never gets
     * here.**
     *
     * There is no `@PathVariable weddingId` and there may never be one on any
     * handler. `{weddingId}` is a value the caller chose; what makes it theirs is
     * their seat, and [CurrentWedding] resolution is the only thing that
     * checks it (notes/2026-08-10-decision-auth-gate-and-sequence.md). The path
     * variable is therefore read by the resolver, which is also why an id that is
     * not a number answers 404 rather than 400 — one answer for every id the caller
     * may not have.
     *
     * The `{weddingId}` parameter is declared to springdoc by hand for the same
     * reason: it is in the path template and in no signature, and `web/` generates
     * its client from that document.
     */
    @Operation(summary = "The wedding and its two seats, for a caller who holds one of them")
    @Parameters(
        Parameter(
            name = "weddingId",
            `in` = ParameterIn.PATH,
            required = true,
            schema = Schema(type = "integer", format = "int64"),
        ),
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "The caller holds a seat in this wedding."),
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
    @GetMapping("/weddings/{weddingId}")
    fun read(
        @CurrentWedding wedding: WeddingScope,
    ): WeddingResponse = weddings.read(wedding.id)

    /**
     * 본인 이름 수정 (`#187`, the backend half of `#175`), specified in
     * `docs/api-spec.md` — **the third place a name is written and the first that can
     * be run twice.**
     *
     * **`me` is the whole authorization story, and it is a path segment rather than a
     * check.** The caller's seat is the one carrying their `user_id` in this wedding;
     * there is no seat id and no `side` to send, so writing the partner's name — the
     * one thing 2026-08-22 took out of `POST /weddings` — is unrepresentable rather
     * than refused (notes/2026-08-22-decision-the-seat-name-edit.md §2).
     *
     * **PUT and not PATCH.** A partial update means a member the caller omitted is not
     * written, and this body has one required member: there is no "leave the name
     * alone" to express, because that is not sending the request. Making it a `PATCH`
     * would either give the product a `PATCH` whose member is required — the standing
     * partial-update contract says every member is optional — or an empty-body no-op
     * nobody asked for, and it would cost the Kotlin null check by wrapping the payload
     * in `Patch` (notes/2026-08-22-decision-partial-update-shape.md §1).
     *
     * **This is not `PATCH /weddings/{weddingId}`**, which writes 예식일 and 보증인원 on
     * the `wedding` row. Different table, different owner; the 설정 screen renders both
     * and the endpoints stay split.
     *
     * [WeddingScope] is the first parameter, as on every wedding-scoped handler:
     * resolution runs before the body is read, so an anonymous request is 401 and a
     * stranger's is 404, neither of them a 400 that would list this endpoint's fields
     * to somebody with no claim on the wedding.
     *
     * The `{weddingId}` parameter is declared to springdoc by hand — it is in the path
     * template and in no signature, because no handler may take one.
     */
    @Operation(operationId = "updateSeatName", summary = "Change the caller's own name on their seat")
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
            description = "The wedding and its two seats, with the caller's name as it now stands.",
        ),
        ApiResponse(
            responseCode = "400",
            description = "A blank or over-long name, or a body that could not be read — an omitted or null `name` is this.",
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
    @PutMapping("/weddings/{weddingId}/seats/me", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun renameSeat(
        @CurrentWedding wedding: WeddingScope,
        @Valid @RequestBody request: UpdateSeatNameRequest,
    ): WeddingResponse = weddings.renameSeat(wedding, request)
}
