package com.donghaeng.error

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
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
    fun `an unhandled exception is a masked 500 that leaks nothing about itself`() {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        val response =
            try {
                mockMvc.get("/test-errors/unhandled").andReturn().response
            } finally {
                root.detachAppender(appender)
                appender.stop()
            }

        // One log format across both producers — the identical assertion lives in
        // ErrorDispatchContractTest. The client is told nothing, so the server
        // has to be told everything, in a string an incident can grep once.
        assertThat(appender.list.filter { it.level == Level.ERROR }.map { it.formattedMessage })
            .contains("Responding 500 to /test-errors/unhandled")

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

private const val STATUS_EXCEPTION_MARKER = "leaked-secret-8b1d47-jdbc-password"

/** A string that appears nowhere else, so finding it in a response body is unambiguous. */
private const val UNHANDLED_MARKER = "leaked-secret-3f9a2c-guest-phone-01012345678"

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

    @GetMapping("/server-side-status")
    fun serverSideStatus(): Nothing = throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, STATUS_EXCEPTION_MARKER)

    @GetMapping("/leaky-5xx")
    fun leaky5xx(): Nothing = throw LeakyServerException()
}

internal data class ValidatedRequest(
    @field:NotBlank val name: String,
)
