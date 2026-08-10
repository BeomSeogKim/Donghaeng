package com.donghaeng.error

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.Filter
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import java.net.Socket

/**
 * The error contract asserted where `@WebMvcTest` structurally cannot look: the
 * servlet container's own `ERROR` dispatch.
 *
 * An exception thrown by a `Filter` never reaches the `@ControllerAdvice` — no
 * handler has been resolved yet, so Spring MVC's exception resolvers are not
 * involved at all. The container re-dispatches to `/error` instead, and whatever
 * is mapped there produces the body. `docs/api-spec.md` promises the problem
 * document "without exception, whatever raised it", so that route has to produce
 * the same document — and only a real server can observe it, because `MockMvc`
 * performs no `ERROR` dispatch at all.
 *
 * This is not a hypothetical path. Three Boot filters run today, and the Spring
 * Security chain arriving with #5 is where the spec's 401 and 403 rows are
 * produced.
 *
 * Neither probe path needs a controller: the filter ends the request before any
 * handler is looked up.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "donghaeng.profile=test",
        // So the OpenAPI assertion below reads the real generated document.
        "springdoc.api-docs.enabled=true",
        // No database is involved in the error contract, and booting one here
        // would make this test's failures depend on Testcontainers.
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
            "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
            "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
    ],
)
class ErrorDispatchContractTest {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `an exception thrown by a filter still becomes the masked problem document`() {
        val (response, logged) = errorLevelEventsDuring { restTemplate.getForEntity("/probe/filter-boom", String::class.java) }

        assertThat(response.statusCode.value()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value())

        // The client is told nothing, so the server is told everything — and in
        // the SAME format GlobalErrorHandler uses (asserted there too). Two
        // formats for one event means an incident greps one of them and silently
        // misses half the 500s.
        assertThat(logged.map { it.formattedMessage })
            .contains("Responding 500 to /probe/filter-boom")
        assertThat(response.headers.contentType.toString())
            .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE)

        val body = objectMapper.readTree(response.body)
        assertThat(body["code"].asText()).isEqualTo("INTERNAL_ERROR")
        assertThat(body["detail"].asText()).isEqualTo("An unexpected error occurred.")
        assertThat(body["status"].asInt()).isEqualTo(500)

        // The path the client asked for, not the `/error` the container
        // re-dispatched to — `instance` is documented as "the request path that
        // produced the error".
        assertThat(body["instance"].asText()).isEqualTo("/probe/filter-boom")

        // A member whitelist, same as the advice's 5xx and for the same reason: a
        // blacklist of leaked substrings stays green when the leak arrives under
        // a member nobody thought to name. `BasicErrorController` publishes
        // `timestamp`, `error` and `path`, none of which is in the contract.
        assertThat(body.fieldNames().asSequence().toList())
            .containsExactlyInAnyOrder("type", "title", "status", "detail", "instance", "code")
        assertThat(response.body).doesNotContain(FILTER_MARKER)
    }

    @Test
    fun `a status a filter sets directly carries a code too`() {
        // `/error` is reached with no exception at all here, so the document has
        // to be built from the dispatch attributes rather than from a throwable.
        // This is the shape Spring Security's entry points use.
        val response = restTemplate.getForEntity("/probe/forwarded-404", String::class.java)

        assertThat(response.statusCode.value()).isEqualTo(HttpStatus.NOT_FOUND.value())
        assertThat(response.headers.contentType.toString())
            .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE)

        val body = objectMapper.readTree(response.body)
        assertThat(body["code"].asText()).isEqualTo("NOT_FOUND")
        assertThat(body["status"].asInt()).isEqualTo(404)
        assertThat(body["instance"].asText()).isEqualTo("/probe/forwarded-404")
    }

    @Test
    fun `a client asking for the error path directly gets a 404 and logs nothing`() {
        // `@RequestMapping` maps for DispatcherType.REQUEST as well as ERROR, so
        // `/error` is an ordinary reachable path. With no dispatch attributes on
        // it there is no error to report: answering 500 would fabricate one, and
        // logging it would hand an anonymous client unbounded ERROR-log volume.
        // That attacks the one capability the security record insists on keeping
        // — alerting on spikes in 401/404/429 — and it cannot be rate-limited,
        // because the standing rule is per-wedding and per-link-token and
        // `/error` has neither. `BasicErrorController` was reachable too and
        // logged nothing, so anything louder is a regression we introduced.
        val logged = errorLevelEventsDuring { restTemplate.getForEntity("/error", String::class.java) }

        assertThat(logged.first.statusCode.value()).isEqualTo(HttpStatus.NOT_FOUND.value())
        assertThat(logged.second)
            .describedAs("an unauthenticated GET /error must not be able to write an ERROR log line")
            .isEmpty()

        val body = objectMapper.readTree(logged.first.body)
        assertThat(body["code"].asText()).isEqualTo("NOT_FOUND")
        assertThat(body["status"].asInt()).isEqualTo(404)
    }

    @Test
    fun `a header set before the failure is not published next to the masked body`() {
        // `sendError` clears the response BUFFER, not its headers, and neither
        // StandardHostValve.custom() nor ApplicationDispatcher.forward() resets
        // them — so anything a filter set before it failed is still on the wire.
        // GlobalErrorHandler already returns a fresh HttpHeaders() on 5xx; this
        // producer writes to the live response, so it has to clear them itself or
        // the two producers mask the same body and different headers. The Spring
        // Security chain arriving with #5 is precisely where headers get set
        // before a failure.
        val response = restTemplate.getForEntity("/probe/header-leak", String::class.java)

        assertThat(response.statusCode.value()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value())
        assertThat(response.headers.keys)
            .describedAs("a 5xx publishes no header the failing code happened to have set")
            .doesNotContain(LEAKY_HEADER)
        assertThat(response.headers.toString()).doesNotContain(HEADER_MARKER)
    }

    @Test
    fun `a 4xx keeps the headers that are part of its meaning`() {
        // The boundary of the rule above. `WWW-Authenticate` on a 401 and `Allow`
        // on a 405 ARE the response; clearing headers on 4xx would break them.
        // Nothing a 5xx could say in a header is worth publishing, so the rule
        // stays 5xx-only, and this test is what pins that it stayed.
        val response = restTemplate.getForEntity("/probe/header-4xx", String::class.java)

        assertThat(response.statusCode.value()).isEqualTo(HttpStatus.UNAUTHORIZED.value())
        assertThat(response.headers.getFirst(LEAKY_HEADER)).isEqualTo(HEADER_MARKER)
    }

    @Test
    fun `a status outside the HTTP registry still carries every member the spec promises`() {
        // 499 is real — nginx and Cloudflare both emit it — and it reaches here
        // the moment anything upstream or in the filter chain calls
        // `sendError(499)`. `HttpStatus.resolve` finds nothing for it, which is
        // exactly where `title` and `code` were both derived from, so one input
        // falsified two of the spec's always-present claims at once.
        val response = restTemplate.getForEntity("/probe/unregistered-status", String::class.java)

        assertThat(response.statusCode.value()).isEqualTo(499)

        val body = objectMapper.readTree(response.body)
        assertThat(body.fieldNames().asSequence().toList()).contains("type", "title", "status", "instance", "code")
        assertThat(body["title"].asText()).isNotBlank()
        assertThat(body["code"].asText()).isNotBlank()
        assertThat(body["status"].asInt()).isEqualTo(499)
    }

    @Test
    fun `the container's own error page names neither Tomcat nor an exception`() {
        // The third producer, and the one neither Kotlin class can reach.
        // `|` is rejected by the HTTP connector while parsing the request target,
        // before the request is associated with the servlet context — so no
        // filter, no DispatcherServlet, no ERROR dispatch, and neither
        // GlobalErrorHandler nor ProblemErrorController runs. Tomcat's own
        // ErrorReportValve writes the response, and its defaults are
        // `showReport = true` / `showServerInfo = true`: an HTML page carrying a
        // partial stack trace and the Tomcat version. Boot never sets either.
        //
        // It matters beyond this one malformed request. If ProblemErrorController
        // itself throws during an ERROR dispatch, StandardHostValve.custom()
        // catches it and the response falls to this same valve — which reads
        // ERROR_EXCEPTION, still holding the ORIGINAL throwable. The one code
        // path that exists to be the last line of defence would fail open.
        val raw = rawRequest("GET /a|b HTTP/1.1")

        assertThat(raw).startsWith("HTTP/1.1 400")
        assertThat(raw)
            .describedAs("the container's error page is a human-readable introspection surface")
            .doesNotContain("Apache Tomcat")
            .doesNotContain("Exception Report")
            .doesNotContain("java.lang.")
    }

    @Test
    fun `the error path is absent from the generated OpenAPI document`() {
        // springdoc 2.9.0 has no ErrorController exclusion, and Kotlin `internal`
        // compiles to public — so without @Hidden this controller becomes an
        // operation in the document `web/` generates its TypeScript client from,
        // and the frontend gains a method for a path that is not part of the API.
        // `BasicErrorController` was `@Controller` and largely invisible; this is
        // not, which is why replacing it needed this assertion.
        val document = restTemplate.getForEntity("/v3/api-docs", String::class.java)

        assertThat(document.statusCode.value()).isEqualTo(200)
        assertThat(
            objectMapper
                .readTree(document.body)["paths"]
                ?.fieldNames()
                ?.asSequence()
                ?.toList() ?: emptyList(),
        ).doesNotContain("/error")
    }

    /** Bypasses every HTTP client, so a request line no client would send can be put on the wire. */
    private fun rawRequest(requestLine: String): String =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write("$requestLine\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray())
                flush()
            }
            socket.getInputStream().readBytes().decodeToString()
        }

    /** Runs [block] with an appender on the root logger, returning its result and the ERROR events. */
    private fun <T> errorLevelEventsDuring(block: () -> T): Pair<T, List<ILoggingEvent>> {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        try {
            val result = block()
            return result to appender.list.filter { it.level == Level.ERROR }
        } finally {
            root.detachAppender(appender)
            appender.stop()
        }
    }

    @TestConfiguration
    class ProbeConfiguration {
        @Bean
        fun probeFilter(): FilterRegistrationBean<Filter> =
            FilterRegistrationBean<Filter>(
                Filter { request, response, chain ->
                    when ((request as HttpServletRequest).requestURI) {
                        "/probe/filter-boom" -> throw ServletException(FILTER_MARKER)
                        "/probe/header-leak" -> {
                            (response as HttpServletResponse).setHeader(LEAKY_HEADER, HEADER_MARKER)
                            throw ServletException(FILTER_MARKER)
                        }
                        "/probe/header-4xx" -> {
                            (response as HttpServletResponse).setHeader(LEAKY_HEADER, HEADER_MARKER)
                            response.sendError(HttpStatus.UNAUTHORIZED.value())
                        }
                        "/probe/unregistered-status" -> (response as HttpServletResponse).sendError(499)
                        "/probe/forwarded-404" ->
                            (response as HttpServletResponse).sendError(HttpStatus.NOT_FOUND.value())
                        else -> chain.doFilter(request, response)
                    }
                },
            ).apply {
                addUrlPatterns("/*")
                order = Ordered.HIGHEST_PRECEDENCE
            }
    }
}

/** A string that appears nowhere else, so finding it in a response body is unambiguous. */
private const val FILTER_MARKER = "leaked-secret-6c2e91-filter-internals"

private const val LEAKY_HEADER = "X-Donghaeng-Probe"

private const val HEADER_MARKER = "leaked-secret-4b7a05-in-a-header"
