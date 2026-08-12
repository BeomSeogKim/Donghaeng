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
        // notes/2026-08-12-decision-session-lifetimes.md — and the record exists
        // because THIS TEST NAME used to assert a provenance that did not. The
        // numbers had been picked by an implementor and then pinned by a test
        // claiming a founder had chosen them.
        //
        // The reasoning the numbers hang on: a wedding is planned over about a
        // year and the couple open this a few times a month, so a 14-day idle
        // window signed a monthly user out on every single visit.
        assertThat(properties.idle).isEqualTo(Duration.ofDays(30))
        assertThat(properties.absolute).isEqualTo(Duration.ofDays(180))

        // The knock-on, stated because nobody would look for it: the touch
        // threshold is derived from `idle`, so it moved too — 30 hours, making the
        // effective idle window 28.75-30 days. `docs/api-spec.md` publishes the
        // range rather than the round number.
        assertThat(properties.touchAfter).isEqualTo(Duration.ofHours(30))
    }

    @Test
    fun `a session that has not been used for longer than the idle window is refused`() {
        val token = issueAt(created = Instant.now(), lastSeen = Instant.now().minus(Duration.ofDays(31)))

        assertThat(me(token).statusCode()).isEqualTo(401)
    }

    @Test
    fun `a session older than the absolute window is refused however recently it was used`() {
        // The one expiry no configuration file can express:
        // `server.servlet.session.timeout` gives idle only, so an application that
        // set it and stopped there would have a session that lives forever as long
        // as it is used — and would look configured.
        val token = issueAt(created = Instant.now().minus(Duration.ofDays(181)), lastSeen = Instant.now())

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
        val justInside = Instant.now().minus(Duration.ofDays(29))
        val token = issueAt(created = Instant.now().minus(Duration.ofDays(179)), lastSeen = justInside)

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
    fun `logging out with a guessed selector cannot end someone else's session`() {
        // Logout takes the same constant-time verifier comparison the read path
        // does, and for a reason that is easy to miss: the selector is a PUBLIC
        // handle. Without the check, anyone who guessed or observed one could sign
        // a stranger out at will — not a data breach, but a denial of service on
        // the couple's own ledger, delivered by an unauthenticated request.
        val genuine = sessions.issue(userId, presented = null)
        val forged = HttpCookie(SessionTokens.COOKIE_NAME, "${genuine.selector}.not-the-verifier")

        assertThat(post("/auth/logout", listOf(forged)).statusCode()).isEqualTo(204)

        // Still alive: the 204 above is the "you are not logged in on this device"
        // answer, not evidence that anything was revoked.
        assertThat(me(cookie(genuine)).statusCode()).isEqualTo(200)
        assertThat(sessionRows.findBySelector(genuine.selector)!!.revokedAt).isNull()
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
