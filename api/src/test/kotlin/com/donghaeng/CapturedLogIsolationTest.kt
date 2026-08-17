package com.donghaeng

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * The assumption [capturingLog] rests on, asserted instead of described.
 *
 * The appender goes on the ROOT logger and cannot be filtered by thread — both
 * 5xx producers write their record on a container thread, not on the thread that
 * made the request. So a capture window collects everything the JVM logs while it
 * is open, and what keeps one test's events out of another test's assertions is
 * only that the windows never overlap.
 *
 * That is true because tests run **sequentially inside one JVM**, and it stops
 * being true the moment `junit.jupiter.execution.parallel.enabled` is turned on.
 * The failure would not be a red suite: it would be a *green* one, with one
 * test's ERROR line satisfying another's assertion — the exact shape #67 was
 * filed about, arriving through the mechanism written to close it.
 *
 * **Gradle's `maxParallelForks` is not this.** A fork is a separate JVM and
 * therefore a separate logger context, so forks may multiply freely; only
 * parallelism *within* a JVM breaks the capture.
 *
 * Read through [ExtensionContext] rather than off a file, because the value is
 * resolved from three places — the launcher request, system properties, and a
 * `junit-platform.properties` that does not currently exist — and a check that
 * reads a missing file passes for the wrong reason.
 */
class CapturedLogIsolationTest {
    @JvmField
    @RegisterExtension
    val configuration = ResolvedConfiguration()

    @Test
    fun `tests run sequentially in one JVM, which is what makes log capture readable`() {
        assertThat(configuration.parameter(PARALLEL_ENABLED))
            .describedAs(
                "%s is on: shared-appender log capture cannot tell two concurrent tests apart, " +
                    "so every log assertion in this suite may now be reading another test's events",
                PARALLEL_ENABLED,
            ).isNotEqualTo("true")
    }

    /** Holds the context JUnit hands an extension, which is the only route to a resolved parameter. */
    class ResolvedConfiguration : BeforeEachCallback {
        private lateinit var context: ExtensionContext

        override fun beforeEach(context: ExtensionContext) {
            this.context = context
        }

        fun parameter(key: String): String? = context.getConfigurationParameter(key).orElse(null)
    }

    private companion object {
        const val PARALLEL_ENABLED = "junit.jupiter.execution.parallel.enabled"
    }
}
