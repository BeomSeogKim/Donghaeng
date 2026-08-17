package com.donghaeng.error

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import com.donghaeng.SharedPostgres
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestComponent
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import javax.sql.DataSource

/**
 * The masking half of the 5xx contract, on the one path where it was confirmed
 * broken: a foreign exception message carrying a row value
 * (notes/2026-08-17-decision-log-masking-mechanism.md).
 *
 * PostgreSQL puts the conflicting value in the error response's `DETAIL` field —
 * `Key (lower(email))=(…) already exists.` — and pgjdbc copies `DETAIL` into the
 * exception message unless `logServerErrorDetail` is off. The client is told
 * nothing about a 500, so [GlobalErrorHandler] logs the whole throwable; that is
 * the pipe the value travelled down. The switch is set in `application.yml` for
 * every profile and asserted on the resolved value by
 * `com.donghaeng.config.ServerErrorDetailGuard`.
 *
 * Two assertions, and they pull against each other on purpose:
 *
 * - **no captured record mentions the colliding value**, anywhere — message,
 *   exception message, or cause chain;
 * - **the throwable is still attached and still names the constraint**, because
 *   masking must never be implemented by logging less of the exception. With the
 *   response masked, the log is the whole diagnosis.
 *
 * A real unique violation, not a hand-built [java.sql.SQLException]: what is under
 * test is what the driver puts in the message, so a fabricated exception would
 * assert our own fixture. The value is bound as a parameter rather than inlined
 * into the SQL, for the same reason — otherwise it would reach the message through
 * the statement text and prove nothing about `DETAIL`.
 *
 * The log capture is written out longhand here, matching [ErrorDispatchContractTest]
 * and [GlobalErrorHandlerTest]. `#67` (branch `api/log-capture`, unmerged at the
 * time of writing) replaces all three with `capturingLog { }` and
 * `assertMaskedFailureRecord`; this file adopts them when that lands rather than
 * shipping a fourth copy now.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["donghaeng.profile=test"],
)
class ServerErrorDetailMaskingTest {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) = SharedPostgres.publish(registry)
    }

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var dataSource: DataSource

    /**
     * The probe's first insert COMMITS — a pooled connection is in autocommit — and
     * [SharedPostgres] is one container shared with every other boot test in the
     * suite. Left behind, the row is a surprise for whatever queries `app_user`
     * next, and it makes this test's own second run depend on its first.
     */
    @AfterEach
    fun removeTheProbeRow() {
        dataSource.connection.use { connection ->
            connection.prepareStatement("delete from app_user where email = ?").use { statement ->
                statement.setString(1, COLLIDING_EMAIL)
                statement.executeUpdate()
            }
        }
    }

    @Test
    fun `a unique violation is logged without the value that collided`() {
        val (response, records) = recordsDuring { restTemplate.getForEntity(PROBE_PATH, String::class.java) }

        assertThat(response.statusCode.value()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value())

        val failure =
            records.singleOrNull { "Responding 500 to $PROBE_PATH" == it.formattedMessage }
                ?: error("the 5xx funnel logged nothing for $PROBE_PATH")

        // The exception is the whole diagnosis, so it stays attached — and the
        // message still names WHICH uniqueness was violated. Dropping either is
        // the wrong way to make the assertion below pass.
        assertThat(failure.throwableProxy)
            .describedAs("a masked 500 is diagnosed from its throwable or not at all")
            .isNotNull()
        assertThat(ThrowableProxyUtil.asString(failure.throwableProxy))
            .contains("duplicate key value violates unique constraint")
            .contains("ux_app_user_email")

        // Every record the request produced, at any level and from any logger —
        // the value would be equally leaked by Hibernate, HikariCP or the driver.
        assertThat(records.map(::render))
            .allSatisfy { record ->
                assertThat(record)
                    .describedAs("a log record quotes the row value that collided")
                    .doesNotContain(COLLIDING_EMAIL)
            }
    }

    private fun render(event: ILoggingEvent): String =
        event.formattedMessage + (event.throwableProxy?.let { ThrowableProxyUtil.asString(it) } ?: "")

    /**
     * Runs [block] with an appender on the root logger, returning its result and
     * everything logged.
     *
     * The snapshot is taken AFTER detaching, and that ordering is the assertion's
     * integrity: the request is served on a Tomcat worker thread, so copying
     * [ListAppender]'s backing `ArrayList` while it is still attached races that
     * thread — and a record lost to the race weakens an `allSatisfy` silently
     * rather than failing it. `#67` (branch `api/log-capture`, unmerged) has this
     * as `capturingLog { }`; this collapses into it when that lands.
     */
    private fun <T> recordsDuring(block: () -> T): Pair<T, List<ILoggingEvent>> {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        val result =
            try {
                block()
            } finally {
                root.detachAppender(appender)
                appender.stop()
            }
        return result to appender.list.toList()
    }

    @TestConfiguration
    class ProbeConfiguration {
        @Bean
        fun duplicateKeyProbe(dataSource: DataSource): DuplicateKeyProbeController = DuplicateKeyProbeController(dataSource)
    }
}

/**
 * Inserts the same verified email twice, so the second statement is refused by
 * `ux_app_user_email` — the live path `#93` found leaking, reduced to two
 * statements.
 *
 * `@TestComponent` is what keeps it to this test. The application's component scan
 * is rooted at `com.donghaeng` and the test classes are on that classpath, so a
 * bare `@RestController` here would map an endpoint that writes to `app_user` into
 * every other `@SpringBootTest` context — and, registered by the scan AND by the
 * `@Bean` above, would fail them all with an ambiguous mapping. Boot's
 * `TypeExcludeFilter` skips a `@TestComponent` during the scan, which leaves the
 * explicit registration as the only one.
 */
@TestComponent
@RestController
class DuplicateKeyProbeController(
    private val dataSource: DataSource,
) {
    @GetMapping(PROBE_PATH)
    fun collide(): Nothing {
        dataSource.connection.use { connection ->
            connection.prepareStatement(INSERT).use { statement ->
                repeat(2) {
                    statement.setString(1, COLLIDING_EMAIL)
                    statement.executeUpdate()
                }
            }
        }
        error("the second insert did not violate ux_app_user_email")
    }
}

private const val PROBE_PATH = "/probe/duplicate-key"

/**
 * Shaped like the data this is about — an account merge key — and distinctive
 * enough that finding it in a log record is unambiguous. `.invalid` is reserved
 * (RFC 2606), so it can never be anyone's address.
 */
private const val COLLIDING_EMAIL = "leaked-row-value-9f31c7@example.invalid"

private const val INSERT =
    "insert into app_user (email, email_verified_by, name, created_at, updated_at) " +
        "values (?, 'GOOGLE', 'probe', now(), now())"
