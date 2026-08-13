package com.donghaeng.auth.session

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Both lifetimes, stated in `application.yml` rather than defaulted here: a
 * default is not a decision, and the environment outranks every file in the jar
 * (api/AGENTS.md, Schema ownership) — so the value that actually applies has to be
 * one someone wrote down.
 */
@ConfigurationProperties("donghaeng.session")
internal data class SessionProperties(
    val idle: Duration,
    val absolute: Duration,
) {
    /**
     * How stale `last_seen_at` may get before a resolve bothers to write.
     *
     * Touching the row on every request is the obvious implementation and the
     * wrong one: it makes every authenticated GET an UPDATE holding a row lock, so
     * requests from one session serialise behind each other — on a product whose
     * defining interaction is a run of rapid attendance taps, each returning a
     * recomputed aggregate.
     *
     * **Be precise about the CSRF half, because this is the file later domains
     * copy.** A GET that writes once every thirty hours is still a
     * state-changing GET; throttling does not make v1's "no state-changing GET"
     * true. What is true is narrower: the only state this particular write
     * changes is the victim's own idle stamp, so a cross-site GET gains an
     * attacker nothing — it refreshes a session they cannot read. That is an
     * argument about THIS write and does not extend to the next one.
     *
     * Derived from [idle] rather than configured, because it is not an independent
     * decision — it is a resolution, and the only thing it can be wrong about is
     * how much of the idle window it spends. The cost is stated exactly: a session
     * can expire up to this long before a full [idle] period of true inactivity
     * has passed, so at the configured 30 days the effective window is 28.75-30
     * days. Nothing expires LATER than the record allows, which is the direction
     * that would matter.
     */
    val touchAfter: Duration get() = idle.dividedBy(TOUCH_DIVISOR)

    private companion object {
        /**
         * A twenty-fourth of the idle window, and the number resolves one trade:
         * **how often an authenticated read takes a row lock, against how much of
         * the published idle window we are willing to hand back.**
         *
         * Larger (a hundredth) writes more often — at 30 days idle that is every
         * seven hours, and the write is per session, on the request path, for a
         * stamp nobody reads until expiry. Smaller (a quarter) writes rarely and
         * spends a week and a half of the window, so a session advertised as 30
         * days could die at 22.
         *
         * 24 costs 30 hours out of 30 days: about 4% of the window, and infrequent
         * enough that a couple's burst of attendance taps writes once.
         *
         * **It is a ratio, so it moves when `idle` moves** — that is the point of
         * deriving it rather than configuring it, and it is the thing to check
         * when the idle number changes. `docs/api-spec.md` publishes the resulting
         * RANGE rather than the round number, so the frontend is never told a
         * precision the server does not keep.
         */
        const val TOUCH_DIVISOR = 24L
    }
}
