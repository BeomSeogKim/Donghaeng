package com.donghaeng.wedding

import io.swagger.v3.oas.annotations.media.Schema

/**
 * The body of `PUT /weddings/{weddingId}/seats/me`, specified in `docs/api-spec.md` —
 * **the caller's own name, and nothing else.**
 *
 * **There is no seat id and no `side` here, and that is the whole design.** Nobody
 * types anybody else's name (notes/2026-08-22-decision-the-couples-two-seats.md), and
 * the way this endpoint keeps that rule is that the other seat cannot be addressed
 * rather than that writing it is refused — a request to pre-fill 신부 이름 before the
 * partner arrives is unrepresentable, not rejected
 * (notes/2026-08-22-decision-the-seat-name-edit.md §2).
 *
 * **PUT and not PATCH, so this is not a [com.donghaeng.json.Patch].** One member, and
 * it is required: there is no state "leave the name alone", because leaving it alone is
 * not sending the request — and every clause of the partial-update contract is about a
 * body with more than one member
 * (notes/2026-08-22-decision-partial-update-shape.md §1).
 *
 * [name] wears [SeatName], the one rule the two places a name already enters wear too.
 * The type is non-null, so an omitted or `null` member fails while the body is read —
 * a 400 `MALFORMED_REQUEST_BODY` rather than a 400 `VALIDATION_FAILED`, which
 * `docs/api-spec.md` states rather than smooths over.
 */
data class UpdateSeatNameRequest(
    @field:SeatName
    @param:Schema(description = "The caller's own name, as it should read on the ledger. Never their partner's", example = "김신랑")
    val name: String,
)
