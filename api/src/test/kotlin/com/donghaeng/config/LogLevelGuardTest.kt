package com.donghaeng.config

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.logging.LogLevel
import org.springframework.boot.logging.LoggerConfiguration
import org.springframework.boot.logging.LoggingSystem

/**
 * The guard's own behaviour, without a logging framework.
 * `DevProfileBootTest`/`ProdProfileBootTest` prove it is wired into the real
 * context; `ProfileConfigurationTest` proves the committed files still state the
 * pins. This is the part that says what the guard does when an environment
 * variable has reversed them
 * (notes/2026-08-17-decision-log-masking-mechanism.md).
 */
class LogLevelGuardTest {
    /** Every pinned logger at `OFF`, which is the committed arrangement. */
    private fun pinned(): MutableMap<String, LogLevel?> {
        val levels = mutableMapOf<String, LogLevel?>()
        PINNED.forEach { levels[it] = LogLevel.OFF }
        return levels
    }

    private fun guardOver(levels: Map<String, LogLevel?>) = LogLevelGuard(StubLoggingSystem(levels))

    private fun refuses(levels: Map<String, LogLevel?>) =
        assertThatThrownBy { guardOver(levels).afterPropertiesSet() }.isInstanceOf(IllegalStateException::class.java)

    private fun starts(levels: Map<String, LogLevel?>) =
        assertThatCode { guardOver(levels).afterPropertiesSet() }.doesNotThrowAnyException()

    @Test
    fun `the committed arrangement starts`() {
        starts(pinned())
    }

    @Test
    fun `each pinned logger refuses on its own at TRACE and at DEBUG`() {
        // One at a time, so the test fails for the logger that regressed rather
        // than for whichever the loop reached first. The list is the guard's own,
        // so a logger added there arrives here already covered.
        PINNED.forEach { name ->
            refuses(pinned().apply { this[name] = LogLevel.TRACE })
            refuses(pinned().apply { this[name] = LogLevel.DEBUG })
        }
    }

    @Test
    fun `the driver is one of them, because logServerErrorDetail does not reach its trace`() {
        // Named separately from the loop above because it is the pipe the `#64`
        // switch provably does NOT close: pgjdbc renders `Detail:` into its own
        // trace unconditionally, one line before the flag is consulted.
        refuses(pinned().apply { this["org.postgresql"] = LogLevel.TRACE })
    }

    @Test
    fun `a level above DEBUG is left alone`() {
        // The boundary is where the guarantee is, not where today's libraries
        // happen to log: nothing above DEBUG carries per-row data, and refusing
        // INFO would make an incident's ordinary verbosity a startup failure.
        listOf(LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR, LogLevel.FATAL, LogLevel.OFF).forEach { level ->
            starts(pinned().apply { this[PINNED.first()] = level })
        }
    }

    @Test
    fun `a logging system that cannot report a level refuses rather than assuming OFF`() {
        // `getLoggerConfiguration` returns null for a logger the system does not
        // know, and "no answer" is not evidence of a safe answer — every logger
        // here is stated in application.yml, so a null means something about the
        // arrangement is not what this guard thinks it is.
        refuses(pinned().apply { this[PINNED.first()] = null })
    }

    private companion object {
        val PINNED =
            listOf(
                "org.hibernate.SQL",
                "org.hibernate.orm.jdbc.bind",
                "org.hibernate.orm.jdbc.extract",
                "org.postgresql",
            )
    }
}

/**
 * Answers levels from a map and nothing else. [LoggingSystem] has one abstract
 * method, so this stays a stub rather than a mock — and the guard reads exactly one
 * thing, which is what makes that honest.
 */
private class StubLoggingSystem(
    private val levels: Map<String, LogLevel?>,
) : LoggingSystem() {
    override fun beforeInitialize() = Unit

    override fun getLoggerConfiguration(loggerName: String): LoggerConfiguration? =
        levels[loggerName]?.let { LoggerConfiguration(loggerName, it, it) }
}
