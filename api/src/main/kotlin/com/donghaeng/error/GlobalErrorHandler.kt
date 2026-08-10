package com.donghaeng.error

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.context.request.WebRequest
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import java.net.URI

/**
 * The one global error handler
 * (notes/2026-08-07-decision-backend-api-conventions.md). Everything below is
 * Spring's own machinery rather than an equivalent of it: extending
 * [ResponseEntityExceptionHandler] inherits an `@ExceptionHandler` for every
 * Spring MVC exception, each already producing an RFC 9457 [ProblemDetail], and
 * Spring fills in `instance` from the request path and serialises as
 * `application/problem+json` on its own.
 *
 * Two consequences of that inheritance are worth knowing before changing this
 * file.
 *
 * **This bean replaces Boot's.** `spring.mvc.problemdetails.enabled=true` in
 * `application.yml` registers `ProblemDetailsExceptionHandler`
 * `@ConditionalOnMissingBean(ResponseEntityExceptionHandler.class)` — so from
 * the moment this class exists, the property changes nothing about MVC
 * responses. It stays set because it is the value whose opposite is wrong, and
 * because it is what the decision record names.
 *
 * **[handleExceptionInternal] is the funnel for everything this class answers.**
 * Every inherited `handle*` method delegates to it except
 * `handleAsyncRequestNotUsableException`, which returns `null` without a body —
 * correctly, since by then the client has already disconnected and there is
 * nothing to write a response to. So `code` and the 5xx mask are stated once
 * each rather than per exception type.
 *
 * It is not, however, the funnel for every error the API returns. An exception
 * raised before a handler is resolved — a `Filter` throwing, a `Filter` calling
 * `sendError` — never reaches an `@ExceptionHandler` at all; the container
 * re-dispatches it to `/error`. [ProblemErrorController] is what makes that
 * route produce the same document, and it is the half of the guarantee that a
 * `@WebMvcTest` structurally cannot observe.
 */
@RestControllerAdvice
internal class GlobalErrorHandler : ResponseEntityExceptionHandler() {
    /**
     * Applies the two rules that hold for every error response, whatever
     * produced it: it carries a `code`, and a 5xx says nothing about itself.
     */
    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        // Called for its committed-response check, which returns null when there
        // is no longer a response to write to.
        val response = super.handleExceptionInternal(ex, body, headers, statusCode, request) ?: return null

        if (statusCode.is5xxServerError) {
            // Masking is a property of the status, not of one handler. A
            // ResponseStatusException(500, reason) carries its own body and never
            // reaches @ExceptionHandler(Exception) below, so its `reason` — which
            // is whatever the throwing code happened to put there — would
            // otherwise be published verbatim.
            //
            // The client is told nothing, so the server has to be told
            // everything. One format shared with ProblemErrorController, so an
            // incident greps for one string instead of finding half the 500s.
            //
            // The path is included and the query string is not. So a token
            // travelling as `?t=…` is not logged — but a token travelling as
            // `/r/{token}` (the likelier shape for the RSVP links, when they
            // return) IS logged in plaintext, and is reflected to the client in
            // `instance` besides. Covering that is the link tokens' own problem
            // when they land, not something this line already handles.
            logger.error(ProblemDocuments.logLine(statusCode, instanceOf(request)), ex)

            // A fresh document, not an edit of the thrower's — see
            // ProblemDocuments.masked. Headers are dropped for the same reason:
            // an ErrorResponse may carry its own, and nothing a 5xx could say in
            // a header is worth publishing.
            return ResponseEntity(ProblemDocuments.masked(statusCode, instanceOf(request)), HttpHeaders(), statusCode)
        }

        val problem = response.body as? ProblemDetail ?: return response
        if (problem.properties?.containsKey(DomainException.CODE) != true) {
            // A DomainException has already set its own; this fills in the rest.
            problem.setProperty(DomainException.CODE, codeFor(ex, statusCode))
        }
        return response
    }

    /**
     * The request path, the same value Spring itself puts in `instance` —
     * except that Spring's version throws on a path outside RFC 3986's grammar,
     * which the connector normally rejects but one Boot property re-admits. See
     * [ProblemDocuments.pathOrNull].
     */
    private fun instanceOf(request: WebRequest): URI? = ProblemDocuments.pathOrNull((request as? ServletWebRequest)?.request?.requestURI)

    /**
     * Anything not already claimed by an inherited handler. Without it the
     * exception escapes to the servlet container and comes back through
     * `BasicErrorController`, whose body is neither `problem+json` nor carries a
     * `code`.
     */
    @ExceptionHandler(Exception::class)
    fun handleUnhandled(
        ex: Exception,
        request: WebRequest,
    ): ResponseEntity<Any>? =
        // Body, mask and log are all applied by the funnel above.
        handleExceptionInternal(
            ex,
            ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR),
            HttpHeaders(),
            HttpStatus.INTERNAL_SERVER_ERROR,
            request,
        )

    /**
     * Named codes exist only where the frontend has to tell two outcomes at the
     * same status apart. Everything else falls back to the status name, so
     * `code` is never absent and never invented — a domain error declares its
     * own by extending [DomainException].
     */
    private fun codeFor(
        ex: Exception,
        statusCode: HttpStatusCode,
    ): String =
        when (ex) {
            // Two exceptions, one failure as far as the client is concerned:
            // Spring raises the first for a `@Valid @RequestBody` DTO and the
            // second for constraints on `@RequestParam`/`@PathVariable`
            // (Spring 6.1+). Mapping only the first would make VALIDATION_FAILED
            // arrive from some endpoints and not others.
            is MethodArgumentNotValidException, is HandlerMethodValidationException -> "VALIDATION_FAILED"
            is HttpMessageNotReadableException -> "MALFORMED_REQUEST_BODY"
            else -> ProblemDocuments.statusName(statusCode)
        }
}
