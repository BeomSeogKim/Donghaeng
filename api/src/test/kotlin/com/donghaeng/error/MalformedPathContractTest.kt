package com.donghaeng.error

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.donghaeng.SharedPostgres
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.Socket

/**
 * Both producers turn a request path into a `java.net.URI` and a dispatch
 * attribute into an `HttpStatusCode`, and both conversions throw
 * `IllegalArgumentException` on input outside their grammar.
 *
 * Stock Tomcat rejects the throwing characters at the connector, which is what
 * makes the unguarded version look unreachable. `server.tomcat.relaxed-path-chars`
 * is exactly one Boot property, and `SERVER_TOMCAT_RELAXED_PATH_CHARS` in a
 * deploy platform sets it with the whole suite green — the same shape as the
 * `server.error.*` reversal. This test buys that property deliberately so the
 * guard is asserted rather than assumed, and so the assertion does not depend on
 * a connector default nobody here controls.
 *
 * What it costs when unguarded is not a stack trace but the contract: the client
 * asked for a path that does not exist and gets a masked 500, and an anonymous
 * caller gets an ERROR log line per request — the same log-volume problem as a
 * direct `GET /error`.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "donghaeng.profile=test",
        "server.tomcat.relaxed-path-chars=|",
    ],
)
class MalformedPathContractTest {
    companion object {
        /**
         * This test boots the whole application, which since #37 means a context
         * that cannot be built without a DataSource. See [SharedPostgres] for why
         * the autoconfiguration exclusions that used to stand here are gone.
         */
        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) = SharedPostgres.publish(registry)
    }

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `a path that is not a valid URI is an ordinary 404, and is not logged as a failure`() {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)

        val raw =
            try {
                rawRequest("GET /a|b HTTP/1.1")
            } finally {
                root.detachAppender(appender)
                appender.stop()
            }

        assertThat(raw).startsWith("HTTP/1.1 ${HttpStatus.NOT_FOUND.value()}")
        assertThat(raw).contains("application/problem+json")

        val body = objectMapper.readTree(raw.substringAfter("\r\n\r\n").substringAfter("\n").substringBefore("\n0\r\n"))
        assertThat(body["status"].asInt()).isEqualTo(404)
        assertThat(body["code"].asText()).isEqualTo("NOT_FOUND")

        assertThat(appender.list.filter { it.level == Level.ERROR })
            .describedAs("a nonexistent path is not a server failure and must not be logged as one")
            .isEmpty()
    }

    private fun rawRequest(requestLine: String): String =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write("$requestLine\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray())
                flush()
            }
            socket.getInputStream().readBytes().decodeToString()
        }
}
