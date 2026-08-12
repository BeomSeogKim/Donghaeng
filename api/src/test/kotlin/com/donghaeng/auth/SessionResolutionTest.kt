package com.donghaeng.auth

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.net.HttpCookie
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * What a session must refuse. Each test here corresponds to one mechanism that
 * would otherwise be a sentence in a record with nothing measuring it — idle
 * expiry, absolute expiry, and the constant-time verifier comparison — and each
 * fails if that mechanism is taken out.
 *
 * They drive `/auth/me` over HTTP rather than calling [SessionService], because a
 * resolver that is not wired into Spring MVC would pass every direct call.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@ActiveProfiles("dev")
@Import(StubGoogleRegistration::class)
internal class SessionResolutionTest : GoogleLoginFixture() {
    @Autowired private lateinit var users: AppUserRepository

    @Autowired private lateinit var identities: OauthIdentityRepository

    @Autowired private lateinit var sessionRows: UserSessionRepository

    @Autowired private lateinit var sessions: SessionService

    @Autowired private lateinit var properties: SessionProperties

    private var userId: Long = 0

    @BeforeEach
    fun clean() {
        sessionRows.deleteAll()
        identities.deleteAll()
        users.deleteAll()
        userId = users.save(AppUser(name = "테스터")).id
    }

    @Test
    fun `the configured lifetimes are the ones the founder decided`() {
        // A value nobody asserts is a value an environment variable can change
        // without anything noticing.
        assertThat(properties.idle).isEqualTo(Duration.ofDays(14))
        assertThat(properties.absolute).isEqualTo(Duration.ofDays(90))
    }

    @Test
    fun `a session that has not been used for longer than the idle window is refused`() {
        val token = issueAt(created = Instant.now(), lastSeen = Instant.now().minus(Duration.ofDays(15)))

        assertThat(me(token).statusCode()).isEqualTo(401)
    }

    @Test
    fun `a session older than the absolute window is refused however recently it was used`() {
        // The one expiry no configuration file can express:
        // `server.servlet.session.timeout` gives idle only, so an application that
        // set it and stopped there would have a session that lives forever as long
        // as it is used — and would look configured.
        val token = issueAt(created = Instant.now().minus(Duration.ofDays(91)), lastSeen = Instant.now())

        assertThat(me(token).statusCode()).isEqualTo(401)
    }

    @Test
    fun `resolving does not write the idle stamp again until it has gone stale`() {
        // The throttle had no test, and deleting the `if` left the suite green:
        // the only assertion touching it backdated the row far past the threshold,
        // so the condition was always true there. A check nobody has watched fail
        // is not a check — and this is a session path, where that standard is not
        // negotiable.
        val issuedAt = Instant.now()
        val token = sessions.issue(userId, presented = null, now = issuedAt)

        // A second later: inside the window, so nothing is written. Every
        // authenticated request taking a row lock is what this exists to avoid.
        sessions.resolve(token, now = issuedAt.plusSeconds(1))
        assertThat(sessionRows.findBySelector(token.selector)!!.lastSeenAt)
            .isCloseTo(issuedAt, within(1, ChronoUnit.MILLIS))

        // Past the threshold: written, or idle expiry would measure from login
        // rather than from the last request.
        val later = issuedAt.plus(properties.touchAfter).plusSeconds(60)
        sessions.resolve(token, now = later)
        assertThat(sessionRows.findBySelector(token.selector)!!.lastSeenAt)
            .isCloseTo(later, within(1, ChronoUnit.MILLIS))
    }

    @Test
    fun `a session inside both windows resolves, and using it moves the idle window`() {
        val justInside = Instant.now().minus(Duration.ofDays(13))
        val token = issueAt(created = Instant.now().minus(Duration.ofDays(89)), lastSeen = justInside)

        assertThat(me(token).statusCode()).isEqualTo(200)

        // Idle expiry means "since the last request", not "since login", and that
        // is only true if resolving touches the row.
        assertThat(sessionRows.findBySelector(selectorOf(token))!!.lastSeenAt).isAfter(justInside)
    }

    @Test
    fun `a real selector with a wrong verifier is refused, and refused the same way an unknown one is`() {
        // This is what the split token buys, and the claim is narrow: the row is
        // found by a value that carries no authority, so the verifier comparison
        // is the entire gate — delete it and this test starts returning 200. A
        // single `where token_hash = ?` would not have been INSECURE; it would
        // have put the only comparison inside a btree, where nothing can watch it
        // fail (notes/2026-08-12-decision-session-token-shape.md).
        val genuine = sessions.issue(userId, presented = null)
        val forged = HttpCookie(SessionTokens.COOKIE_NAME, "${genuine.selector}.not-the-verifier")

        assertThat(me(forged).statusCode()).isEqualTo(401)
        assertThat(me(HttpCookie(SessionTokens.COOKIE_NAME, "unknown-selector.whatever")).statusCode())
            .isEqualTo(401)
        // The genuine one still works, so the test above failed for the right
        // reason.
        assertThat(me(cookie(genuine)).statusCode()).isEqualTo(200)
    }

    @Test
    fun `two session cookies are refused rather than one of them being picked`() {
        // Our cookie carries no Domain, but a sibling host under the same
        // registrable domain can set one WITH a Domain that covers us — and the
        // deployment shape invites it: `web/` on Cloudflare Pages, this API on a
        // VPS, one registrable domain. The browser then sends both, in an order
        // no RFC fixes and with no way for us to tell which host set which.
        //
        // Taking the first match would seat the victim inside the attacker's
        // session: every write they make lands in the attacker's ledger, and
        // nothing looks wrong to either party. Refusing the ambiguous case costs a
        // re-login.
        val genuine = sessions.issue(userId, presented = null)
        val planted = sessions.issue(userId, presented = null)

        assertThat(me(cookie(genuine)).statusCode()).isEqualTo(200)
        assertThat(get("/auth/me", listOf(cookie(planted), cookie(genuine))).statusCode()).isEqualTo(401)
        assertThat(get("/auth/me", listOf(cookie(genuine), cookie(planted))).statusCode()).isEqualTo(401)
    }

    @Test
    fun `a malformed or absent cookie is simply not a session`() {
        assertThat(get("/auth/me").statusCode()).isEqualTo(401)
        assertThat(me(HttpCookie(SessionTokens.COOKIE_NAME, "no-separator")).statusCode()).isEqualTo(401)
        assertThat(me(HttpCookie(SessionTokens.COOKIE_NAME, ".")).statusCode()).isEqualTo(401)
    }

    /** Issues a real session, then backdates its row — the timestamps are the subject. */
    private fun issueAt(
        created: Instant,
        lastSeen: Instant,
    ): HttpCookie {
        val token = sessions.issue(userId, presented = null)
        val row = sessionRows.findBySelector(token.selector)!!
        sessionRows.save(
            UserSession(
                selector = row.selector,
                verifierHash = row.verifierHash,
                userId = row.userId,
                createdAt = created,
                lastSeenAt = lastSeen,
                id = row.id,
            ),
        )
        return cookie(token)
    }

    private fun cookie(token: SessionToken) = HttpCookie(SessionTokens.COOKIE_NAME, token.cookieValue)

    private fun selectorOf(cookie: HttpCookie) = cookie.value.substringBefore('.')

    private fun me(cookie: HttpCookie) = get("/auth/me", listOf(cookie))
}
