package com.donghaeng.wedding

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * 초대 수락 — the token the partner is holding, and **their own name**.
 *
 * **Nobody types anybody else's name** (notes/2026-08-22-decision-the-couples-two-seats.md).
 * There is no `side` here and there could not be one: the seat already exists and the
 * invite points at it, so which side this is was settled when the wedding was created.
 * The person accepting supplies exactly one thing about themselves.
 *
 * **[token] carries no `@NotBlank`, and that is deliberate.** Every token this endpoint
 * cannot use gets one answer — `INVITE_NOT_FOUND` — and an empty string is one of them.
 * Validating it would make a blank token a 400 while a wrong one is a 404, which is a
 * distinction only somebody probing would care about, and a second answer for `web/` to
 * write copy for. It is a required member either way: the type is non-null, so an
 * omitted `token` fails while the body is read.
 *
 * **[name] is validated exactly as `POST /weddings` validates the same column** —
 * `@Size(max = 100)` is the `varchar(100)` it lands in, measured as sent and before the
 * trim, and `@NotBlank` refuses a name the trim would empty. One client-side rule
 * covers both screens.
 */
data class JoinWeddingRequest(
    @param:Schema(description = "The invite token, read from the link's fragment. Never put it in a URL path")
    val token: String,
    @field:NotBlank
    @field:Size(max = 100)
    @param:Schema(description = "The accepting person's own name, as it should read on the ledger", example = "이신부")
    val name: String,
)
