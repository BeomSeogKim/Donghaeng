package com.donghaeng

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/**
 * What the server wrote down while [block] ran.
 *
 * A masked 5xx tells the client nothing, so the server record is the whole
 * diagnosis — and a log line no test reads is a line that can be deleted with the
 * suite green. Four tests had grown their own attach/detach for that, which is
 * how the appender itself became something to get wrong rather than something to
 * use.
 *
 * The appender goes on the ROOT logger and is not filtered by thread on purpose:
 * both producers write their line on a container thread, not on the thread that
 * made the request, so a thread filter would capture nothing at all in the boot
 * tests.
 *
 * Two properties this has to hold, since the whole suite shares one JVM:
 *
 * - **Nothing is left attached.** The detach is in a `finally`, so a failing
 *   block does not leave an appender collecting every later test's events —
 *   which would also feed them to whichever assertion still held a reference.
 * - **The captured list stops moving before it is read.** It is snapshotted after
 *   the detach, so an event arriving late on a container thread cannot mutate the
 *   list under an assertion.
 *
 * Windows are exclusive only because tests run sequentially. Nothing here makes
 * that true; turning on parallel execution would need this revisited.
 */
internal fun <T> capturingLog(block: () -> T): Pair<T, CapturedLog> {
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
    return result to CapturedLog(appender.list.toList())
}

internal class CapturedLog(
    private val events: List<ILoggingEvent>,
) {
    fun at(level: Level): List<ILoggingEvent> = events.filter { it.level == level }

    /**
     * Every event, whatever its level and whoever wrote it.
     *
     * The 4xx record is located through this rather than through [at], for two
     * reasons the 5xx assertions do not have. Its level is itself asserted, and
     * selecting by level first would turn "the record was demoted" into a missing
     * element rather than a level mismatch. And the leak assertions must read
     * lines this suite never wrote: a request value that surfaces under some
     * other logger's line is the same leak.
     */
    fun everything(): List<ILoggingEvent> = events
}
