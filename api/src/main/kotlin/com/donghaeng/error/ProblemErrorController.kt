package com.donghaeng.error

import io.swagger.v3.oas.annotations.Hidden
import jakarta.servlet.RequestDispatcher
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.apache.commons.logging.LogFactory
import org.springframework.boot.web.servlet.error.ErrorController
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI

/**
 * The second producer of the problem document, and the reason
 * `docs/api-spec.md` can say "without exception, whatever raised it".
 *
 * [GlobalErrorHandler] only sees exceptions Spring MVC's exception resolvers are
 * given — which means a handler was already resolved. Anything that fails before
 * or outside that never reaches it: a `Filter` throwing, a `Filter` calling
 * `sendError`, a servlet-level failure. The container re-dispatches those to
 * `/error`, where Boot's own `BasicErrorController` would answer with
 * `application/json` carrying `timestamp`/`status`/`error`/`path` — no `code`,
 * no `instance`, and, decisively, **no 5xx mask**.
 *
 * That is not a hypothetical route. Three Boot filters run today, and the Spring
 * Security chain arriving with #5 produces the spec's 401 and 403 from exactly
 * here.
 *
 * Declaring an [ErrorController] bean is what removes `BasicErrorController`:
 * Boot registers it `@ConditionalOnMissingBean(ErrorController::class)`.
 *
 * One consequence to state precisely, because the obvious reading of it is
 * wrong. `server.error.include-message` and `include-exception` are read by
 * `BasicErrorController`, so from here they decide nothing about this response.
 * `include-stacktrace` is NOT in that group: Boot's
 * `TomcatWebServerFactoryCustomizer` hardens Tomcat's own `ErrorReportValve`
 * only when it resolves to `never`, so that one still decides something real,
 * on a page neither producer serves. See [TomcatErrorPageHardening], which makes
 * that hardening unconditional. All three stay pinned in `application.yml`.
 *
 * `@Hidden` because springdoc 2.9.0 has no `ErrorController` exclusion and
 * Kotlin `internal` compiles to public: without it `/error` becomes an operation
 * in the generated OpenAPI document, and `web/` generates a client method for a
 * path that is not part of the API. `BasicErrorController` was `@Controller` and
 * largely invisible; this is not.
 *
 * Content type is set explicitly rather than negotiated. This API serves JSON
 * only, and a client that sent `Accept: text/html` gets the problem document
 * rather than a whitelabel page — there is no HTML in this application to fall
 * back to.
 */
@Hidden
@RestController
internal class ProblemErrorController : ErrorController {
    private val logger = LogFactory.getLog(javaClass)

    @RequestMapping("\${server.error.path:\${error.path:/error}}")
    fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<ProblemDetail> {
        // `@RequestMapping` maps for DispatcherType.REQUEST too, so this path is
        // reachable by anyone. No ERROR_STATUS_CODE means the container did not
        // re-dispatch here — the client simply asked for `/error`, and nothing
        // failed. Answering 500 would fabricate an incident, and logging it would
        // give an anonymous client unbounded ERROR-log volume against the one
        // capability the security record keeps: alerting on 401/404/429 spikes
        // (notes/2026-07-30-decision-network-security.md). It cannot be
        // rate-limited either — the standing rule is per-wedding and per-link
        // token, and this path has neither, while IP-only is forbidden.
        //
        // `BasicErrorController` was equally reachable and wrote no ERROR line,
        // so anything louder than [notAnErrorDispatch]'s INFO record is a
        // regression this class introduced.
        val status = statusOf(request) ?: return notAnErrorDispatch(request)

        // The path the client actually asked for. `/error` is where the
        // container forwarded to, and publishing that as `instance` would tell
        // the frontend nothing about which request failed.
        //
        // ERROR_REQUEST_URI excludes the query string — the container keeps that
        // in a separate attribute this class never reads. So a token carried in
        // a query string is neither logged nor reflected. A token carried in the
        // PATH is not covered, by either of us; see GlobalErrorHandler.
        //
        // Parsed through pathOrNull, not URI.create: the connector is what
        // normally keeps an unparseable path off this line, and it takes one Boot
        // property to stop doing so.
        val instance = ProblemDocuments.pathOrNull(request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) as? String)

        if (!status.is5xxServerError) {
            logClientError(status, instance)
            return problemResponse(status, instance)
        }

        // The other half of masking, and the half GlobalErrorHandler gets for
        // free by returning a fresh HttpHeaders(). Here the response is live:
        // `sendError` clears its buffer but not its headers, and neither
        // StandardHostValve.custom() nor ApplicationDispatcher.forward() resets
        // them — so a header set by whatever failed is still on the wire next to
        // the masked body. `reset()` is the servlet API's own way to drop them,
        // and it is 5xx-only on purpose: `WWW-Authenticate` on a 401 and `Allow`
        // on a 405 ARE the response, while nothing a 5xx could say in a header
        // is worth publishing.
        if (!response.isCommitted) response.reset()

        logger.error(
            ProblemDocuments.logLine(status, instance),
            request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) as? Throwable,
        )
        return ResponseEntity
            .status(status)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(ProblemDocuments.masked(status, instance))
    }

    /**
     * The same 404 any other unmapped path gets — and, since #65, recorded the
     * same way. It is still quiet in the sense that matters: an anonymous client
     * cannot make it write an ERROR line and so cannot drown the incident
     * channel. Leaving it out of the INFO record instead would put the single
     * hole in the 404 signal at the one path an anonymous caller can always
     * reach.
     */
    private fun notAnErrorDispatch(request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        // The path is logged and deliberately not published: `instance` stays
        // null so the response is indistinguishable from any other unmapped
        // path's, while the log still says which path was asked for.
        logClientError(HttpStatus.NOT_FOUND, ProblemDocuments.pathOrNull(request.requestURI))
        return problemResponse(HttpStatus.NOT_FOUND, instance = null)
    }

    /**
     * The 4xx record. See [GlobalErrorHandler] for why it is INFO, why it carries
     * no throwable, and what `#5` will have to add to it for a cross-tenant
     * refusal to be distinguishable from a typo'd id.
     */
    private fun logClientError(
        status: HttpStatusCode,
        instance: URI?,
    ) {
        if (status.is4xxClientError) logger.info(ProblemDocuments.logLine(status, instance))
    }

    private fun problemResponse(
        status: HttpStatusCode,
        instance: URI?,
    ): ResponseEntity<ProblemDetail> =
        ResponseEntity
            .status(status)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(
                ProblemDocuments.forStatus(status, instance).apply {
                    setProperty(DomainException.CODE, ProblemDocuments.statusName(status))
                },
            )

    /**
     * `null` when this was not an `ERROR` dispatch at all.
     *
     * `HttpStatusCode.valueOf` rather than `HttpStatus.resolve`, so a status
     * outside the registry is echoed instead of being rewritten to 500.
     */
    private fun statusOf(request: HttpServletRequest): HttpStatusCode? =
        ProblemDocuments.statusOrNull(request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) as? Int)
}
