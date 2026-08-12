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
)

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
     * Writes, on a read path: touching `last_seen_at` is what makes idle expiry
     * mean "since the last request" rather than "since login".
     */
    @Transactional
    fun resolve(
        token: SessionToken,
        now: Instant = Instant.now(),
    ): Long? {
        val session = sessions.findBySelector(token.selector) ?: return null
        if (!token.matches(session.verifierHash)) return null
        if (!session.isUsableAt(now, properties.idle, properties.absolute)) return null
        session.lastSeenAt = now
        return session.userId
    }
}
