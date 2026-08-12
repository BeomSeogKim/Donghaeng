package com.donghaeng.auth

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.support.TransactionTemplate
import java.net.HttpCookie
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

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

    @Autowired private lateinit var transactions: TransactionTemplate

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
    fun `logging out with two session cookies ends both, so signing out cannot complete a takeover`() {
        // The attack this closes, in order (notes/2026-08-12-decision-session-cookie-ambiguity.md):
        //
        //   1. a sibling host plants a `Domain`-scoped cookie holding the
        //      ATTACKER's own valid token;
        //   2. every request now carries two, so resolution refuses and the couple
        //      see a broken app;
        //   3. they press sign-out — or the frontend calls logout on a 401.
        //
        // With logout reading the single unambiguous token, step 3 revoked NOTHING
        // and then cleared our cookie. A `Set-Cookie` without a `Domain` can only
        // delete the host-only one, so the planted cookie was left alone in the jar
        // and still valid — and the next request resolved cleanly as the attacker.
        // The sign-out gesture was the last step of the takeover.
        val attacker = users.save(AppUser(name = "공격자")).id
        val victimSession = sessions.issue(userId, presented = null)
        val plantedSession = sessions.issue(attacker, presented = null)
        val both = listOf(cookie(victimSession), cookie(plantedSession))

        // Step 2: ambiguity is still refused on the read path, and must stay so.
        assertThat(get("/auth/me", both).statusCode()).isEqualTo(401)

        assertThat(post("/auth/logout", both).statusCode()).isEqualTo(204)

        // Revocation is greedy where resolution is strict. Both rows die.
        assertThat(sessionRows.findBySelector(victimSession.selector)!!.revokedAt).isNotNull()
        assertThat(sessionRows.findBySelector(plantedSession.selector)!!.revokedAt).isNotNull()

        // And the outcome that matters: the cookie we cannot delete now carries a
        // token that is worth nothing.
        assertThat(me(cookie(plantedSession)).statusCode()).isEqualTo(401)
    }

    @Test
    fun `a resolve that lands mid-logout cannot bring the session back`() {
        // Two transactions, because one cannot show this. Hibernate writes every
        // updatable column of a dirty entity from ITS OWN snapshot, so a resolve
        // that loaded the row before the logout committed would write
        // `revoked_at = null` back over it — a lost update that answers 204 and
        // leaves the token alive for the rest of its 180 days.
        //
        // The realistic shape, and it is the case logout exists for: someone opens
        // the app on a device after days away and taps sign-out while the page's
        // first /auth/me is still in flight.
        val token = sessions.issue(userId, presented = null)
        backdate(token, lastSeen = Instant.now().minus(Duration.ofHours(31)))

        val resolveLoaded = CountDownLatch(1)
        val logoutCommitted = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()

        val resolving =
            thread {
                runCatching {
                    transactions.execute {
                        // Loads the row and dirties `last_seen_at` — the touch is
                        // due, which is what makes this transaction a writer.
                        sessions.resolve(token)
                        resolveLoaded.countDown()
                        check(logoutCommitted.await(10, TimeUnit.SECONDS)) { "logout never committed" }
                    }
                }.onFailure(failure::set)
            }

        check(resolveLoaded.await(10, TimeUnit.SECONDS)) { "resolve never loaded the row" }
        transactions.execute { sessions.revoke(token) }
        logoutCommitted.countDown()
        resolving.join()
        failure.get()?.let { throw it }

        assertThat(sessionRows.findBySelector(token.selector)!!.revokedAt)
            .describedAs("the resolve wrote its snapshot back over the revocation")
            .isNotNull()
        assertThat(me(cookie(token)).statusCode()).isEqualTo(401)
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
        backdate(token, lastSeen = lastSeen, created = created)
        return cookie(token)
    }

    private fun backdate(
        token: SessionToken,
        lastSeen: Instant,
        created: Instant = Instant.now(),
    ) {
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
    }

    private fun cookie(token: SessionToken) = HttpCookie(SessionTokens.COOKIE_NAME, token.cookieValue)

    private fun selectorOf(cookie: HttpCookie) = cookie.value.substringBefore('.')

    private fun me(cookie: HttpCookie) = get("/auth/me", listOf(cookie))
}
