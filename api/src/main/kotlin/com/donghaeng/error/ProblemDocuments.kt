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
     * The request attribute a scope resolver sets when it refuses a request, read
     * by [GlobalErrorHandler] and by nothing that writes a response.
     *
     * It travels on the request rather than in the document because the document is
     * the thing that must NOT differ: a cross-tenant refusal answers exactly what an
     * id nobody owns answers, or the pair is a wedding-id oracle
     * (notes/2026-08-10-decision-cross-tenant-status-code.md). The cost of that is a
     * membership failure and a typo'd id being indistinguishable in a log too, and
     * this is what pays it back.
     *
     * **What it marks is "a wedding-scoped request was refused", not "this was an
     * attack".** It does not separate a stranger's wedding from an id that does not
     * exist — telling those apart needs a query on the refusal path, and the alert
     * this feeds (a spike in 401/404) does not need them apart. It does separate a
     * refused resolution from every other 404 this API serves, which nothing did
     * before.
     */
    val SCOPE_REFUSED = "com.donghaeng.error.scope-refused"

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

    /**
     * One format for both producers, so an incident greps for one string.
     *
     * **The path is truncated because the caller chooses its length.** The
     * request line counts against Tomcat's 8KB header budget
     * (`server.max-http-request-header-size`), so without a bound every 4xx —
     * which since #65 is every 404, from anyone, unauthenticated — writes up to
     * ~8KB of bytes the caller picked, against a normal record of a few dozen.
     * That is a log-volume lever held by the attacker rather than by us, and the
     * cheapest place to take it away is the one line both producers share.
     *
     * It removes the SIZE lever only. **How often an unauthenticated caller may
     * reach a 4xx at all is still open** — the standing rate-limit unit is per
     * wedding and per link token, and a pre-auth path has neither (`#98`).
     *
     * [MAX_PATH_CHARS] is far above any path this API serves, so a real request
     * is never cut; when something is cut, [TRUNCATION_MARK] says so in the
     * record, because a silently shortened path reads as the whole path and
     * would send an incident looking for the wrong request.
     */
    fun logLine(
        status: HttpStatusCode,
        instance: URI?,
        scopeRefused: Boolean = false,
    ): String =
        "Responding ${status.value()} to ${loggablePath(instance)}" +
            if (scopeRefused) " ($SCOPE_REFUSED_NOTE)" else ""

    private fun loggablePath(instance: URI?): String {
        val path = instance?.toString() ?: UNKNOWN_PATH
        if (path.length <= MAX_PATH_CHARS) return path
        // Never cut between a surrogate pair: `server.tomcat.relaxed-path-chars`
        // admits a raw non-ASCII path, and a lone surrogate is what makes a JSON
        // log encoder emit something that is not valid UTF-8.
        val end = if (path[MAX_PATH_CHARS - 1].isHighSurrogate()) MAX_PATH_CHARS - 1 else MAX_PATH_CHARS
        return path.take(end) + TRUNCATION_MARK
    }

    /** Greppable, and it says what happened rather than what we suspect of the caller. */
    private const val SCOPE_REFUSED_NOTE = "wedding scope refused"

    private const val UNTITLED = "Error"

    private const val UNTITLED_CODE = "ERROR"

    private const val UNKNOWN_PATH = "an unknown path"

    /** Generous next to `/weddings/{id}/guests/{id}/meal-counts`, and 60x below the request-line budget. */
    private const val MAX_PATH_CHARS = 120

    private const val TRUNCATION_MARK = "…[truncated]"
}
