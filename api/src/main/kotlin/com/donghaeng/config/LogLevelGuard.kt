package com.donghaeng.config

import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.logging.LogLevel
import org.springframework.boot.logging.LoggingSystem
import org.springframework.stereotype.Component

/**
 * Refuses to start an environment in which a logger that prints row values is
 * verbose (notes/2026-08-17-decision-log-masking-mechanism.md).
 *
 * `application.yml` pins all four to `OFF`, and pinning them there is exactly
 * what an environment variable walks past:
 * `LOGGING_LEVEL_ORG_HIBERNATE_ORM_JDBC_BIND=TRACE` typed into a deploy platform
 * outranks every file in the jar, with the committed-file sweep in
 * `ProfileConfigurationTest` still green — the same argument
 * [SchemaOwnershipGuard] and [ServerErrorDetailGuard] are built on, applied to
 * the pipe next to them.
 *
 * The four, and what each one publishes:
 *
 * - `org.hibernate.orm.jdbc.bind` — the parameters sent, i.e. 하객 names, phone
 *   numbers, emails, a session token's hash.
 * - `org.hibernate.orm.jdbc.extract` — the same values read back.
 * - `org.hibernate.SQL` — the statements, which is how the other two become
 *   readable.
 * - `org.postgresql` — **the one `logServerErrorDetail` does not reach.**
 *   `QueryExecutorImpl` traces `FE=> Bind(stmt=…,$1=<value>)` with literal
 *   parameters, and traces the server's error message through
 *   `ServerErrorMessage.toString()`, which renders `Detail:`, `Hint:`,
 *   `Position:` and `Where:` unconditionally — the flag is consulted on the
 *   NEXT line, when the exception is constructed, so it cannot suppress this
 *   one. Boot installs `SLF4JBridgeHandler` and `LevelChangePropagator`, so the
 *   driver's `java.util.logging` output arrives in logback and follows this
 *   level.
 *
 * ## Why DEBUG and not only TRACE
 *
 * The refusal is `DEBUG or below` rather than the exact level each logger uses,
 * because the level a library logs a value at is the library's choice and not
 * ours: pgjdbc's parameter traces are `FINEST`, Hibernate's binds are `TRACE`,
 * and neither is a promise. What is stable is that nothing above DEBUG carries
 * per-row data, so the boundary is drawn where the guarantee is rather than where
 * today's implementations happen to sit.
 *
 * It reads the RESOLVED level through [LoggingSystem], which is the logging
 * framework's own view after `logging.level.*`, the environment and any external
 * `logback-spring.xml` have all been applied. Reading the property would see only
 * the first of those.
 */
@Component
internal class LogLevelGuard(
    private val loggingSystem: LoggingSystem,
) : InitializingBean {
    override fun afterPropertiesSet() {
        PINNED.forEach { name ->
            val effective = loggingSystem.getLoggerConfiguration(name)?.effectiveLevel
            check(effective != null && effective !in VERBOSE) {
                "`logging.level.$name` resolved to ${describe(effective)}, and that logger prints row values — " +
                    "하객 names, phone numbers, emails. It is pinned to `OFF` in application.yml for every " +
                    "profile; an environment may not reverse it."
            }
        }
    }

    private fun describe(level: LogLevel?) = if (level == null) "nothing this logging system can report" else "`$level`"

    private companion object {
        /**
         * `org.postgresql` is the package and not one class on purpose: the traces
         * live in `core.v3.QueryExecutorImpl` today, and a driver upgrade that moves
         * them is not something a pin should have to follow.
         */
        val PINNED =
            listOf(
                "org.hibernate.SQL",
                "org.hibernate.orm.jdbc.bind",
                "org.hibernate.orm.jdbc.extract",
                "org.postgresql",
            )

        val VERBOSE = setOf(LogLevel.TRACE, LogLevel.DEBUG)
    }
}
