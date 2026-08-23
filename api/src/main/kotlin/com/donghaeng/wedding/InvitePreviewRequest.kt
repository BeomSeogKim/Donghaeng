package com.donghaeng.wedding

import io.swagger.v3.oas.annotations.media.Schema

/**
 * 초대 수락 화면이 무엇을 수락하는지 묻는 요청 — the token, and nothing else.
 *
 * **A POST that reads, and the method is forced by the token.** `docs/api-spec.md`
 * has said since `#181` that the token goes in no path and no query string, ours or
 * anyone's: both are recorded in access logs and reflected in an error document's
 * `instance` (notes/2026-08-22-decision-the-invite-link.md §2). A body is the only
 * place left, and a body means POST. The `consumes` that comes with it is not
 * incidental either — it is what forces the CORS preflight that stands in for a CSRF
 * token in v1.
 *
 * **[token] carries no `@NotBlank`, for the reason [JoinWeddingRequest.token] does**:
 * every token this endpoint cannot use gets one answer, and an empty string is one of
 * them. A 400 for blank beside a 404 for wrong is a distinction only somebody probing
 * would care about.
 */
data class InvitePreviewRequest(
    @param:Schema(description = "The invite token, read from the link's fragment. Never put it in a URL path")
    val token: String,
) {
    /**
     * Masked, for the reason [JoinWeddingRequest] is masked: Spring MVC logs the
     * deserialised body at DEBUG and truncates at 100 characters, which a generated
     * `InvitePreviewRequest(token=…)` fits inside whole. Close the pipe, do not filter
     * it (notes/2026-08-17-decision-log-masking-mechanism.md).
     */
    override fun toString(): String = "InvitePreviewRequest(token=***)"
}
