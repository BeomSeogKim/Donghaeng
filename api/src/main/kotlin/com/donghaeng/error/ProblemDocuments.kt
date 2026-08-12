package com.donghaeng.error

import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import java.net.URI

/**
 * The problem document, built in one place because it is produced from two —
 * [GlobalErrorHandler] for anything Spring MVC resolves a handler for, and
 * [ProblemErrorController] for anything the servlet container re-dispatches to
 * `/error`. `docs/api-spec.md` promises one shape for both.
 */
internal object ProblemDocuments {
    val MASKED_DETAIL = "An unexpected error occurred."
    val INTERNAL_ERROR = "INTERNAL_ERROR"

    /**
     * The 5xx document, built from the status and the path and nothing else.
     *
     * Constructing a fresh [ProblemDetail] rather than editing the one the
     * thrower supplied is the whole point: the masking rule is a whitelist of
     * what may be published, and overwriting `detail` and `code` on an incoming
     * document is a two-member blacklist — it leaves `title`, `instance` and
     * every extension property the thrower set. `ErrorResponseException` lets
     * any code anywhere construct that document, so what it contains is not
     * enumerable from here.
     *
     * `title` is not copied from the thrower either — [forStatus] derives it
     * from the status.
     */
    fun masked(
        status: HttpStatusCode,
        instance: URI?,
    ): ProblemDetail =
        forStatus(status, instance).apply {
            detail = MASKED_DETAIL
            setProperty(DomainException.CODE, INTERNAL_ERROR)
        }

    fun forStatus(
        status: HttpStatusCode,
        instance: URI?,
    ): ProblemDetail =
        ProblemDetail.forStatus(status).apply {
            this.instance = instance
            // Set explicitly rather than left to ProblemDetail's own fallback,
            // which returns null for a status outside the HTTP registry — and
            // `sendError(499)` is real: nginx and Cloudflare both emit it. The
            // spec says `title` is always present, so one unregistered status
            // must not falsify it.
            this.title = HttpStatus.resolve(status.value())?.reasonPhrase ?: UNTITLED
        }

    /**
     * The fallback `code`: never absent, never invented. A status outside the
     * registry has no name to take, which is the only case that reaches
     * [UNTITLED_CODE] — still a stable string the frontend can switch on, which
     * is all `code` promises.
     */
    fun statusName(status: HttpStatusCode): String = HttpStatus.resolve(status.value())?.name ?: UNTITLED_CODE

    /**
     * `null` rather than a thrown `IllegalArgumentException` for a path outside
     * RFC 3986's grammar.
     *
     * Stock Tomcat rejects those characters at the connector, so unguarded this
     * looks unreachable — but `server.tomcat.relaxed-path-chars` is one Boot
     * property, and `SERVER_TOMCAT_RELAXED_PATH_CHARS` in a deploy platform sets
     * it with the whole suite green. Throwing here costs the contract: the
     * response becomes a masked 500 for a path that simply does not exist, and
     * every such request writes an ERROR line.
     *
     * `instance` is the member that goes missing when this returns null. That is
     * the right trade — it is diagnostic, and the alternative is publishing a
     * path we could not parse.
     */
    fun pathOrNull(path: String?): URI? = path?.let { runCatching { URI.create(it) }.getOrNull() }

    /** `null` for a status outside `HttpStatusCode`'s range, guarded for the same reason. */
    fun statusOrNull(status: Int?): HttpStatusCode? = status?.let { runCatching { HttpStatusCode.valueOf(it) }.getOrNull() }

    /** One format for both producers, so an incident greps for one string. */
    fun logLine(
        status: HttpStatusCode,
        instance: URI?,
    ): String = "Responding ${status.value()} to ${instance ?: "an unknown path"}"

    private const val UNTITLED = "Error"

    private const val UNTITLED_CODE = "ERROR"
}
