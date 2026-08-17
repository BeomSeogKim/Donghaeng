package com.donghaeng.error

import ch.qos.logback.classic.Level
import com.donghaeng.CapturedLog
import org.assertj.core.api.Assertions.assertThat

/**
 * The server half of the 5xx contract, asserted identically for both producers —
 * [GlobalErrorHandler] and [ProblemErrorController] — because they promise one
 * format so an incident greps once. It lives here rather than in either test so
 * that a change to one producer's line cannot quietly leave the other's behind.
 *
 * Three things are checked, and each of them was a mutant that survived or nearly
 * did: the record exists **at ERROR** (a demotion to WARN drops it out of an
 * alert), it names the real **status and path** (not a constant, not a hardcoded
 * 500), and it carries the **throwable**. The last is the one nothing held: the
 * client is told nothing on a 5xx, so a record without the exception says an
 * incident happened and nothing about what it was.
 *
 * The throwable is asserted by TYPE and never by message. `#64` masks what the
 * message may contain — pgjdbc quotes the conflicting value on a unique violation
 * — and an assertion on message text here would either forbid that change or have
 * to be rewritten by it. What `#64` must not do is drop the throwable, and that
 * is exactly what this refuses.
 *
 * Only our own records are considered: the container logs its own ERROR line for
 * the same failure, and asserting on everything at ERROR would make this a test
 * of Tomcat's logging.
 */
internal fun CapturedLog.assertMaskedFailureRecord(
    status: Int,
    path: String,
    cause: Class<out Throwable>?,
) {
    val records = at(Level.ERROR).filter { it.formattedMessage.startsWith(RECORD_PREFIX) }

    assertThat(records.map { it.formattedMessage })
        .describedAs("the client is told nothing about a %d, so this record is the whole diagnosis", status)
        .containsExactly("$RECORD_PREFIX$status to $path")

    val throwable = records.single().throwableProxy
    if (cause == null) {
        // `sendError(503)` reaches ProblemErrorController with no exception at
        // all, and inventing one would be worse than recording none.
        assertThat(throwable).describedAs("nothing threw, so nothing is attached").isNull()
    } else {
        assertThat(throwable?.className)
            .describedAs("a masked 5xx recorded without its exception is an incident with no diagnosis")
            .isEqualTo(cause.name)
    }
}

/** [ProblemDocuments.logLine]'s own opening, which is what makes the record ours. */
private const val RECORD_PREFIX = "Responding "
