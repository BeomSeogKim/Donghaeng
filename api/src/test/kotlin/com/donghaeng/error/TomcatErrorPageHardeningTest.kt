package com.donghaeng.error

import jakarta.servlet.DispatcherType
import jakarta.servlet.Filter
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServletRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered
import java.net.Socket
import java.util.EnumSet

/**
 * The container's own error page, asserted with Boot's hardening of it
 * deliberately switched off.
 *
 * `server.error.include-stacktrace=always` is the whole point of this file, not
 * an incidental setting. Boot hardens `ErrorReportValve` only under
 * `include-stacktrace: never`, so under the committed configuration this test
 * would pass on Boot's work and prove nothing about ours — the vacuous-green
 * shape this repo keeps getting bitten by. Set to `always`, Boot adds no
 * hardened valve at all, and the only thing standing between the client and
 * `Apache Tomcat/10.1.55` plus a stack trace is [TomcatErrorPageHardening].
 *
 * Verified by removing that class and re-running: the body then contains
 * `Type Exception Report`, the exception message, a partial stack trace and the
 * Tomcat version.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "donghaeng.profile=test",
        "server.error.include-stacktrace=always",
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
            "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
            "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
    ],
)
class TomcatErrorPageHardeningTest {
    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `a failure during the error dispatch does not fall through to a Tomcat report page`() {
        // A filter registered for the ERROR dispatch that throws is the reachable
        // form of "the last line of defence itself failed". Spring MVC never gets
        // it — the container is already unwinding — so StandardHostValve falls
        // through to ErrorReportValve, which still holds the ORIGINAL throwable
        // in RequestDispatcher.ERROR_EXCEPTION. Spring Security's chain registers
        // for the ERROR dispatch by default, so #5 puts real code on this path.
        val raw = rawRequest("GET /probe/error-dispatch-boom HTTP/1.1")

        assertThat(raw).startsWith("HTTP/1.1 500")
        assertThat(raw)
            .describedAs("the container's error page is a human-readable introspection surface")
            .doesNotContain("Apache Tomcat")
            .doesNotContain("Exception Report")
            .doesNotContain(ORIGINAL_FAILURE_MARKER)
            .doesNotContain("jakarta.servlet")
    }

    @Test
    fun `a request target the connector rejects is answered without a report either`() {
        // `|` is invalid in a request target, so this is rejected during parsing —
        // before the request is associated with the servlet context. Neither
        // producer runs, which is the counterexample docs/api-spec.md's error
        // section now scopes its guarantee around.
        val raw = rawRequest("GET /a|b HTTP/1.1")

        assertThat(raw).startsWith("HTTP/1.1 400")
        assertThat(raw).doesNotContain("Apache Tomcat").doesNotContain("Exception Report")
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

    @TestConfiguration
    class ErrorDispatchBoomConfiguration {
        @Bean
        fun errorDispatchBoomFilter(): FilterRegistrationBean<Filter> =
            FilterRegistrationBean<Filter>(
                Filter { request, response, chain ->
                    val servletRequest = request as HttpServletRequest
                    when {
                        servletRequest.dispatcherType == DispatcherType.ERROR ->
                            throw ServletException("failed while handling the failure")

                        servletRequest.requestURI == "/probe/error-dispatch-boom" ->
                            throw ServletException(ORIGINAL_FAILURE_MARKER)

                        else -> chain.doFilter(request, response)
                    }
                },
            ).apply {
                addUrlPatterns("/*")
                setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST, DispatcherType.ERROR))
                order = Ordered.HIGHEST_PRECEDENCE
            }
    }
}

private const val ORIGINAL_FAILURE_MARKER = "leaked-secret-9e5b32-the-original-failure"
