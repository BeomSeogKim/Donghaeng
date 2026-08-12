package com.donghaeng.auth

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

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
     * copy.** A GET that writes once every fourteen hours is still a
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
     * has passed, so the effective idle window is between 13 days and 14. Nothing
     * expires LATER than the record allows, which is the direction that would
     * matter.
     */
    val touchAfter: Duration get() = idle.dividedBy(TOUCH_DIVISOR)

    private companion object {
        const val TOUCH_DIVISOR = 24L
    }
}

/**
 * Issues and resolves sessions. The only thing in the application that turns a
 * token into a user id, which is why every rule about session tokens is either in
 * here or in [SessionToken].
 */
@Service
internal class SessionService(
    private val sessions: UserSessionRepository,
    private val properties: SessionProperties,
) {
    /**
     * Mints a session for [userId] and **revokes the one the caller presented**.
     *
     * That second half is session-fixation defense
     * (notes/2026-07-30-decision-network-security.md, Tokens): a browser that
     * arrives at login already holding a session must leave holding a different
     * one, or an identifier planted before login keeps working after it. It
     * revokes only the presented session, never the user's others — logging in on
     * a laptop does not sign the phone out.
     */
    @Transactional
    fun issue(
        userId: Long,
        presented: SessionToken?,
        now: Instant = Instant.now(),
    ): SessionToken {
        presented?.let { token ->
            sessions.findBySelector(token.selector)?.let { existing ->
                if (token.matches(existing.verifierHash)) existing.revokedAt = now
            }
        }

        val issued = SessionToken.mint()
        sessions.save(
            UserSession(
                selector = issued.selector,
                verifierHash = issued.verifierHash,
                userId = userId,
                createdAt = now,
                lastSeenAt = now,
            ),
        )
        return issued
    }

    /**
     * The user this token stands for, or `null` — one answer for every way a
     * session can fail to be a session. An unknown selector, a wrong verifier, a
     * revoked row, an idled-out row and an absolutely-expired row are
     * indistinguishable to the caller on purpose; telling them apart would let an
     * anonymous caller learn which selectors exist.
     *
     * Writes only when the idle stamp has actually gone stale — see
     * [SessionProperties.touchAfter] for why a read path may not write every time.
     */
    @Transactional
    fun resolve(
        token: SessionToken,
        now: Instant = Instant.now(),
    ): Long? {
        val session = sessions.findBySelector(token.selector) ?: return null
        if (!token.matches(session.verifierHash)) return null
        if (!session.isUsableAt(now, properties.idle, properties.absolute)) return null
        if (now.isAfter(session.lastSeenAt.plus(properties.touchAfter))) session.lastSeenAt = now
        return session.userId
    }
}
