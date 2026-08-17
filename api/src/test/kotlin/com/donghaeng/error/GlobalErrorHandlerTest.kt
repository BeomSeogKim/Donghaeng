package com.donghaeng.error

import ch.qos.logback.classic.Level
import com.donghaeng.capturingLog
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.ErrorResponseException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * The error contract every endpoint returns, asserted on the serialized JSON
 * rather than on the handler's return value — the wire shape is what
 * `docs/api-spec.md` promises and what `web/` switches on.
 *
 * There are no domain endpoints yet, so the requests are driven through a
 * controller that exists only in the test source set. `controllers` names it
 * explicitly: left unset, the slice instantiates every `@Controller` on the
 * classpath, so the day `GuestController` lands this file goes red on a missing
 * `GuestService` — a failure with nothing to do with the error contract.
 *
 * What this test cannot see is the servlet container's `ERROR` dispatch, which
 * `MockMvc` does not perform. That half of the contract is
 * [ErrorDispatchContractTest].
 */
@WebMvcTest(controllers = [ErrorContractController::class], properties = ["donghaeng.profile=test"])
// Since #37 the application has a Spring Security filter chain, and a `@WebMvcTest`
// slice does not include the `@Configuration` that declares ours — it auto-configures
// Boot's default one instead, which answers 401 to everything and would make every
// assertion below fail for a reason this file is not about. The real chain is
// `permitAll` in every environment (notes/2026-08-10-decision-auth-gate-and-sequence.md),
// so removing filters here restores what the application actually does rather than
// papering over a difference. The chain as it really runs is asserted by
// ErrorDispatchContractTest, which boots the whole application.
@AutoConfigureMockMvc(addFilters = false)
class GlobalErrorHandlerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `a domain exception becomes an RFC 9457 problem detail carrying its code`() {
        val response =
            mockMvc
                .get("/test-errors/domain")
                .andReturn()
                .response

        assertThat(response.status).isEqualTo(HttpStatus.NOT_FOUND.value())
        assertThat(response.contentType).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE)

        val body = objectMapper.readTree(response.contentAsString)
        assertThat(body["code"].asText()).isEqualTo("TEST_THING_NOT_FOUND")
        assertThat(body["status"].asInt()).isEqualTo(404)
        assertThat(body["title"].asText()).isEqualTo("Not Found")
        assertThat(body["type"].asText()).isEqualTo("about:blank")
        assertThat(body["detail"].asText()).isEqualTo("The thing was not found.")
        assertThat(body["instance"].asText()).isEqualTo("/test-errors/domain")
    }

    @Test
    fun `a request DTO failing Bean Validation is a 400 with the validation code`() {
        val response =
            mockMvc
                .post("/test-errors/validated") {
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"name": ""}"""
                }.andReturn()
                .response

        assertThat(response.status).isEqualTo(HttpStatus.BAD_REQUEST.value())
        assertThat(response.contentType).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE)

        val body = objectMapper.readTree(response.contentAsString)
        assertThat(body["code"].asText()).isEqualTo("VALIDATION_FAILED")
        assertThat(body["status"].asInt()).isEqualTo(400)
        assertThat(body["instance"].asText()).isEqualTo("/test-errors/validated")
    }

    @Test
    fun `a constraint on a request parameter fails with the same validation code`() {
        // Spring 6.1+ raises HandlerMethodValidationException — not
        // MethodArgumentNotValidException — for constraints on @RequestParam and
        // @PathVariable. Same failure to the client, so it has to be the same
        // `code`: docs/api-spec.md promises VALIDATION_FAILED for a request that
        // failed Bean Validation, and the frontend cannot switch on a code that
        // never arrives from half the endpoints.
        val response =
            mockMvc
                .get("/test-errors/param-validated?name=x")
                .andReturn()
                .response

        assertThat(response.status).isEqualTo(HttpStatus.BAD_REQUEST.value())

        val body = objectMapper.readTree(response.contentAsString)
        assertThat(body["code"].asText()).isEqualTo("VALIDATION_FAILED")
    }

    @Test
    fun `an unparseable request body is a 400 distinguishable from a validation failure`() {
        val response =
            mockMvc
                .post("/test-errors/validated") {
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"name": """
                }.andReturn()
                .response

        assertThat(response.status).isEqualTo(HttpStatus.BAD_REQUEST.value())

        val body = objectMapper.readTree(response.contentAsString)
        assertThat(body["code"].asText()).isEqualTo("MALFORMED_REQUEST_BODY")
    }

    @Test
    fun `a 4xx leaves one INFO record carrying its status and path`() {
        // The 4xx branch is the only application-side source for the security
        // record's 401/404/429 spike alerting
        // (notes/2026-07-30-decision-network-security.md), and it is the only
        // place a cross-tenant 404 can ever be told apart from a typo'd id — the
        // response is identical on purpose
        // (notes/2026-08-10-decision-cross-tenant-status-code.md).
        val (_, logged) =
            capturingLog {
                mockMvc.get("/test-errors/domain?token=$QUERY_MARKER") {
                    header(PROBE_HEADER, HEADER_MARKER)
                }
            }

        val records = logged.everything().filter { it.formattedMessage.startsWith("Responding 404") }
        assertThat(records).hasSize(1)

        val record = records.single()
        // Status and path, in the format ProblemDocuments.logLine already gives
        // the 5xx line — one string for an incident to grep, not two.
        assertThat(record.formattedMessage).isEqualTo("Responding 404 to /test-errors/domain")

        // INFO, not WARN: a 401 from an anonymous caller is routine traffic, and
        // a channel that cries wolf gets muted. Not DEBUG either — that is one
        // config change away from silent, and #64 has just pinned loggers off.
        assertThat(record.level).isEqualTo(Level.INFO)

        // No throwable. The 5xx line carries one because the client is told
        // nothing and the exception is the whole diagnosis; a 4xx is a decision
        // we made deliberately, and a stack trace per 404 is how a log stops
        // being readable.
        assertThat(record.throwableProxy).isNull()
    }

    @Test
    fun `the 4xx record carries the status and the path and nothing else about the request`() {
        // "상태와 경로만" — the record's phrasing. The whole captured stream is
        // searched, not just the line we produced, because a leak that arrives
        // under some other logger's line is the same leak.
        val (_, logged) =
            capturingLog {
                mockMvc.post("/test-errors/validated?token=$QUERY_MARKER") {
                    contentType = MediaType.APPLICATION_JSON
                    // The marker is the REJECTED VALUE, so it sits inside the
                    // exception Spring raises. A handler that logged `ex.message`
                    // — the obvious way to make a 4xx line "more useful" —
                    // publishes it, and this assertion is what goes red.
                    content = """{"name": "ok", "note": "$BODY_MARKER"}"""
                    header(PROBE_HEADER, HEADER_MARKER)
                }
            }

        assertThat(logged.everything().map { it.formattedMessage })
            .contains("Responding 400 to /test-errors/validated")

        assertThat(logged.everything().joinToString("\n") { "${it.formattedMessage} ${it.throwableProxy?.message}" })
            .describedAs("a 4xx record names the status and the path, never the body, a header or the query string")
            .doesNotContain(BODY_MARKER)
            .doesNotContain(HEADER_MARKER)
            .doesNotContain(QUERY_MARKER)
    }

    @Test
    fun `a path at the bound is recorded whole`() {
        // The bound sits far above anything this API routes, so the record of a
        // real request is never cut. If this ever goes red because a genuine path
        // outgrew it, raise the bound — do not soften the assertion below.
        val path = "/" + "a".repeat(119)

        val (_, logged) = capturingLog { mockMvc.get(path) }

        assertThat(logged.everything().map { it.formattedMessage })
            .contains("Responding 404 to $path")
    }

    @Test
    fun `a caller-inflated path is cut, and the record says it was cut`() {
        // The request line counts against Tomcat's 8KB header budget, so without
        // this the caller picks how many bytes each of their 404s writes — ~250x
        // a normal record, from an unauthenticated request, against the one
        // capability the security record keeps. The cut is marked because a
        // silently shortened path reads as the whole path.
        val path = "/" + "a".repeat(4_000)

        val (_, logged) = capturingLog { mockMvc.get(path) }

        val record = logged.everything().single { it.formattedMessage.startsWith("Responding 404") }
        assertThat(record.formattedMessage)
            .startsWith("Responding 404 to /" + "a".repeat(119))
            .endsWith("…[truncated]")
        assertThat(record.formattedMessage.length)
            .describedAs("the caller does not choose how long the record is")
            .isLessThan(200)
    }

    @Test
    fun `an unhandled exception is a masked 500 that leaks nothing about itself`() {
        val (response, logged) = capturingLog { mockMvc.get("/test-errors/unhandled").andReturn().response }

        // One log format across both producers — the identical assertion lives in
        // ErrorDispatchContractTest, through the same function. The client is told
        // nothing, so the server has to be told everything: the status, the path,
        // and the exception that neither of them names.
        logged.assertMaskedFailureRecord(500, "/test-errors/unhandled", IllegalStateException::class.java)

        assertThat(response.status).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value())
        assertThat(response.contentType).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE)

        val body = objectMapper.readTree(response.contentAsString)
        assertThat(body["code"].asText()).isEqualTo("INTERNAL_ERROR")
        assertThat(body["detail"].asText()).isEqualTo("An unexpected error occurred.")
        assertThat(body["title"].asText()).isEqualTo("Internal Server Error")

        // A whitelist of members, not a blacklist of leaked substrings. A test
        // shaped as `doesNotContain("IllegalStateException")` is the shape that
        // passes for the wrong reason — it stays green when the leak arrives
        // under a member nobody thought to name, `trace` being the obvious one.
        assertThat(body.fieldNames().asSequence().toList())
            .containsExactlyInAnyOrder("type", "title", "status", "detail", "instance", "code")

        // And the whole serialized body, so a leak nested inside an allowed
        // member is caught too.
        assertThat(response.contentAsString)
            .doesNotContain(UNHANDLED_MARKER)
            .doesNotContain("IllegalStateException")
    }

    @Test
    fun `a server-side ResponseStatusException does not publish its reason either`() {
        // Spring puts a ResponseStatusException's `reason` straight into `detail`,
        // because it is an ErrorResponse carrying its own body — so masking only
        // the exceptions that reach @ExceptionHandler(Exception) would leave this
        // route open. Masking is a property of the 5xx status, not of one handler.
        val response =
            mockMvc
                .get("/test-errors/server-side-status")
                .andReturn()
                .response

        assertThat(response.status).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value())

        val body = objectMapper.readTree(response.contentAsString)
        assertThat(body["detail"].asText()).isEqualTo("An unexpected error occurred.")
        assertThat(body["code"].asText()).isEqualTo("INTERNAL_ERROR")
        assertThat(response.contentAsString).doesNotContain(STATUS_EXCEPTION_MARKER)
    }

    @Test
    fun `a 5xx that is not a 500 is recorded as the status it actually was`() {
        // The masking rule is a property of the whole 5xx range, and so is the
        // record. With only 500 asserted, a line hardcoded to "Responding 500"
        // stayed green — and an incident reading it would be looking for a crash
        // while the application was in fact refusing traffic.
        val (response, logged) = capturingLog { mockMvc.get("/test-errors/unavailable").andReturn().response }

        assertThat(response.status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value())
        logged.assertMaskedFailureRecord(503, "/test-errors/unavailable", ResponseStatusException::class.java)

        // Masked the same way, since it is the status and not the handler that
        // decides — the body is the 503's, with nothing of the thrower's reason.
        val body = objectMapper.readTree(response.contentAsString)
        assertThat(body["detail"].asText()).isEqualTo("An unexpected error occurred.")
        assertThat(body["code"].asText()).isEqualTo("INTERNAL_ERROR")
        assertThat(response.contentAsString).doesNotContain(UNAVAILABLE_MARKER)
    }

    @Test
    fun `a 5xx publishes only the members we intend, not an edit of the thrower's document`() {
        // The stronger form of the test above. Any code anywhere can construct an
        // ErrorResponseException with a fully populated ProblemDetail, and Spring
        // makes that document the response body verbatim. Masking by overwriting
        // `detail` and `code` on it is a two-member blacklist: `title` and any
        // extension property the thrower set survive it, and neither is
        // enumerable from the handler.
        val response =
            mockMvc
                .get("/test-errors/leaky-5xx")
                .andReturn()
                .response

        assertThat(response.status).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value())

        val body = objectMapper.readTree(response.contentAsString)
        assertThat(body.fieldNames().asSequence().toList())
            .containsExactlyInAnyOrder("type", "title", "status", "detail", "instance", "code")
        assertThat(body["title"].asText()).isEqualTo("Internal Server Error")
        assertThat(body["detail"].asText()).isEqualTo("An unexpected error occurred.")
        assertThat(body["code"].asText()).isEqualTo("INTERNAL_ERROR")
        assertThat(response.contentAsString)
            .doesNotContain(LEAKY_TITLE_MARKER)
            .doesNotContain(LEAKY_PROPERTY_MARKER)
    }

    @Test
    fun `a framework error nobody named still carries a code, taken from the status`() {
        // `code` being always present is what lets the frontend switch on it
        // without a null branch. Spring MVC raises a couple of dozen exceptions
        // this file will never enumerate; the status name is a stable string for
        // all of them, and it is not invented.
        val response =
            mockMvc
                .get("/test-errors/validated") // POST-only
                .andReturn()
                .response

        assertThat(response.status).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED.value())

        val body = objectMapper.readTree(response.contentAsString)
        assertThat(body["code"].asText()).isEqualTo("METHOD_NOT_ALLOWED")
    }
}

/** Markers that appear nowhere else, so finding one in a log record is unambiguous. */
private const val BODY_MARKER = "leaked-secret-1e5b90-in-a-request-body"

private const val HEADER_MARKER = "leaked-secret-9c3d24-in-a-request-header"

private const val QUERY_MARKER = "leaked-secret-5a7e11-in-a-query-string"

private const val PROBE_HEADER = "X-Donghaeng-Probe"

private const val STATUS_EXCEPTION_MARKER = "leaked-secret-8b1d47-jdbc-password"

/** A string that appears nowhere else, so finding it in a response body is unambiguous. */
private const val UNHANDLED_MARKER = "leaked-secret-3f9a2c-guest-phone-01012345678"

private const val UNAVAILABLE_MARKER = "leaked-secret-9e5b28-upstream-host"

private const val LEAKY_TITLE_MARKER = "leaked-secret-7d4e10-in-a-title"

private const val LEAKY_PROPERTY_MARKER = "leaked-secret-2a8f63-in-an-extension-member"

private class TestThingNotFoundException : DomainException("TEST_THING_NOT_FOUND", HttpStatus.NOT_FOUND, "The thing was not found.")

/** A 5xx whose thrower populated every member it could reach. */
private class LeakyServerException :
    ErrorResponseException(
        HttpStatus.INTERNAL_SERVER_ERROR,
        ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, STATUS_EXCEPTION_MARKER).apply {
            title = LEAKY_TITLE_MARKER
            setProperty("upstream", LEAKY_PROPERTY_MARKER)
        },
        null,
    )

@RestController
@RequestMapping("/test-errors")
internal class ErrorContractController {
    @GetMapping("/domain")
    fun domain(): Nothing = throw TestThingNotFoundException()

    @PostMapping("/validated")
    fun validated(
        @Valid @RequestBody request: ValidatedRequest,
    ): ValidatedRequest = request

    @GetMapping("/param-validated")
    fun paramValidated(
        @RequestParam @Size(min = 2) name: String,
    ): String = name

    @GetMapping("/unhandled")
    fun unhandled(): Nothing = throw IllegalStateException(UNHANDLED_MARKER)

    @GetMapping("/unavailable")
    fun unavailable(): Nothing = throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE_MARKER)

    @GetMapping("/server-side-status")
    fun serverSideStatus(): Nothing = throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, STATUS_EXCEPTION_MARKER)

    @GetMapping("/leaky-5xx")
    fun leaky5xx(): Nothing = throw LeakyServerException()
}

internal data class ValidatedRequest(
    @field:NotBlank val name: String,
    /** A field whose REJECTED VALUE is a body value, so the log assertions have something to find. */
    @field:Size(max = 3) val note: String? = null,
)
