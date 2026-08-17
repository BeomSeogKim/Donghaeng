package com.donghaeng.auth.account

import com.donghaeng.SharedPostgres
import com.donghaeng.auth.session.SessionToken
import com.donghaeng.auth.session.UserSessionRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.sql.Connection
import java.time.Duration
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * THE RED GATE OF `#93`: two first logins for one identity arriving at once both
 * end holding a session, against one `app_user` row and no masked 500.
 *
 * It runs against a real Postgres carrying `V1` and `V2` because the failure being
 * fixed is a unique index refusing a second INSERT — a mock of either repository
 * proves nothing about it, and the recovery depends on what the database does with
 * two transactions racing for the same index row.
 *
 * Two kinds of test, and the second exists because the first cannot promise it
 * raced. The barrier test starts two threads together: with the fix it passes,
 * without the fix it fails — but on a run where the two threads happen not to
 * overlap it passes vacuously. The blocked-insert test removes the timing
 * altogether: it holds the index row from an uncommitted transaction of its own,
 * waits until Postgres reports the login as blocked on it, and only then commits.
 * That is the exact interleaving `#93` describes, produced on purpose rather than
 * hoped for.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
internal class ConcurrentFirstLoginTest {
    @Autowired private lateinit var logins: LoginService

    @Autowired private lateinit var users: AppUserRepository

    @Autowired private lateinit var identities: OauthIdentityRepository

    @Autowired private lateinit var sessions: UserSessionRepository

    @Autowired private lateinit var dataSource: DataSource

    @BeforeEach
    fun clean() {
        sessions.deleteAll()
        identities.deleteAll()
        users.deleteAll()
    }

    @Test
    fun `two first logins for one identity arriving at once both come back with a session`() {
        // Both identity axes, because the losing INSERT is refused by a different
        // index in each: the verified email collides on `ux_app_user_email`, and a
        // person whose provider gave us no verified address collides one table over
        // on `ux_oauth_identity_provider_subject`. The second case is also what says
        // the create attempt is ONE transaction — an app_user committed beside a
        // rejected identity row would leave two accounts here.
        listOf("kim@gmail.com", null).forEachIndexed { index, mergeKey ->
            clean()
            val profile = profile(subject = "google-subject-$index", mergeKey = mergeKey)

            val issued = race(profile, profile)

            assertThat(issued).describedAs("both logins issued a session").hasSize(2)
            assertThat(issued.map { it.selector }.toSet()).describedAs("two distinct sessions").hasSize(2)
            assertThat(users.findAll()).describedAs("merge key %s", mergeKey).hasSize(1)
            assertThat(identities.findAll()).hasSize(1)
            assertThat(sessions.findAll()).hasSize(2)
        }
    }

    @Test
    fun `two strangers logging in at the same instant get two accounts`() {
        // Two people arriving together end up with an account each — which is what a
        // create path that treated any integrity failure as "already exists" would
        // get wrong, by seating the second one on the first one's ledger.
        //
        // What this does NOT assert is the other half of the call, "the lock is the
        // identity's own index row and nothing wider": these assertions hold just as
        // well under a global mutex, which would only make the two logins slower. No
        // lock is taken anywhere in the path, so that half is held by construction
        // and by reading the code, and saying otherwise here would be a guarantee
        // with nothing behind it.
        val issued =
            race(
                profile(subject = "google-subject-a", mergeKey = "kim@gmail.com"),
                profile(subject = "google-subject-b", mergeKey = "lee@gmail.com"),
            )

        assertThat(issued).hasSize(2)
        assertThat(users.findAll()).hasSize(2)
        assertThat(identities.findAll()).hasSize(2)
    }

    @Test
    fun `a login blocked on the merge key's index row still ends as a login`() {
        // The race, staged rather than hoped for. The rival connection is the OTHER
        // first login: it inserts the same verified address and does not commit, so
        // the login under test misses its lookup (the row is invisible), reaches its
        // own INSERT, and waits on the index row. It resumes only when the rival
        // commits — and what it gets back is the unique violation #93 is about.
        val profile = profile(subject = "google-subject-email-race", mergeKey = "kim@gmail.com")

        val issued =
            stagedRace(profile, blockedOn = "app_user") { rival ->
                rival.execute(
                    "insert into app_user (email, email_verified_by, name, created_at, updated_at) " +
                        "values ('kim@gmail.com', 'GOOGLE', '먼저', now(), now())",
                )
            }

        // One account — the one the rival committed — and the login that lost the
        // race is attached to it rather than dead. `single()` is what carries that:
        // an account of its own would be a second row.
        val user = users.findAll().single()
        // The rival wrote `먼저`; the retry's merge lookup recognised that row as
        // this person, and recognising someone is what refreshes their name (#94).
        // So the marker of "which row did the login land on" is no longer the name —
        // this asserts the refresh reached the merge path THROUGH the retry, which
        // is the one call site the two changes had to be merged to reach.
        assertThat(user.name).isEqualTo("김테스터")
        assertThat(identities.findAll().single().userId).isEqualTo(user.id)
        assertThat(sessions.findAll().single().userId).isEqualTo(user.id)
        assertThat(issued.selector).isNotBlank()
    }

    @Test
    fun `a login blocked on the subject's index row still ends as a login`() {
        // The SAME staging one table over, because the two axes are two different
        // 500s and only one of them was staged. This is the axis a person whose
        // provider gave us no verified address can only ever collide on — and the
        // barrier test's cover for it is a run that may not have overlapped.
        //
        // The account is committed up front so that the merge lookup succeeds and
        // the login reaches the identity insert, which is where the rival is
        // holding the row.
        val user = users.save(AppUser(email = "kim@gmail.com", emailVerifiedBy = "GOOGLE", name = "먼저"))
        val profile = profile(subject = "google-subject-race", mergeKey = "kim@gmail.com")

        stagedRace(profile, blockedOn = "oauth_identity") { rival ->
            rival.execute(
                "insert into oauth_identity (user_id, provider, provider_user_id, created_at) " +
                    "values (${user.id}, 'GOOGLE', 'google-subject-race', now())",
            )
        }

        assertThat(users.findAll()).hasSize(1)
        assertThat(identities.findAll().single().userId).isEqualTo(user.id)
        assertThat(sessions.findAll().single().userId).isEqualTo(user.id)
    }

    @Test
    fun `a login that loses on one index and then the other still ends as a login`() {
        // TWO rivals, one per axis, and this is the case a two-pass retry answered
        // with the very 500 #93 exists to remove: pass 1 loses on `ux_app_user_email`
        // to the rival holding the merge key, pass 2 loses on
        // `ux_oauth_identity_provider_subject` to the rival holding the subject, and
        // pass 3 finds the identity and returns. It is the bound, staged.
        //
        // The pre-existing account is what the subject rival's identity row points
        // at, and it deliberately holds NO merge key — otherwise the first pass would
        // merge onto it and never reach the email index at all.
        val stranger = users.save(AppUser(name = "제삼자"))
        val profile = profile(subject = "google-subject-both", mergeKey = "kim@gmail.com")
        val pool = Executors.newSingleThreadExecutor()
        try {
            dataSource.connection.use { subjectRival ->
                dataSource.connection.use { emailRival ->
                    subjectRival.autoCommit = false
                    emailRival.autoCommit = false
                    subjectRival.execute(
                        "insert into oauth_identity (user_id, provider, provider_user_id, created_at) " +
                            "values (${stranger.id}, 'GOOGLE', 'google-subject-both', now())",
                    )
                    emailRival.execute(
                        "insert into app_user (email, email_verified_by, name, created_at, updated_at) " +
                            "values ('kim@gmail.com', 'GOOGLE', '먼저', now(), now())",
                    )

                    val login = pool.submit<SessionToken> { logins.login(profile, presented = null) }

                    // Pass 1 is waiting on the merge key. Release it, and it walks
                    // straight into the subject rival on pass 2.
                    awaitBlockedInsert("app_user")
                    emailRival.commit()
                    awaitBlockedInsert("oauth_identity")
                    subjectRival.commit()

                    assertThat(login.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isNotNull()
                }
            }
        } finally {
            pool.shutdownNow()
        }

        // The subject is registered to the stranger's account, so that is where this
        // login lands — the same answer a sequential login against this data gets.
        assertThat(users.findAll()).hasSize(2)
        assertThat(identities.findAll().single().userId).isEqualTo(stranger.id)
        assertThat(sessions.findAll().single().userId).isEqualTo(stranger.id)
    }

    @Test
    fun `a violation of some other constraint is not read as an existing account`() {
        // `ck_app_user_email_shape` refuses `@nope`, and that failure means the
        // application wrote a row it may not write — not "someone got here first".
        //
        // THE ASSERTION IS ON THE CLASSIFIER, not on the outcome, and that is the
        // whole point of this test. Reading this violation as "already exists" is
        // the widening that ends with a session issued against someone else's
        // account — and it changes NOTHING observable here, because a swallowed
        // violation simply retries, fails identically, and is rethrown by the
        // exhaustion path. An earlier version asserted the thrown type and the empty
        // tables; `alreadyRegistered` was mutated to `return true` and it stayed
        // green, which made it decoration.
        val malformed = profile(subject = "google-subject-malformed", mergeKey = "@nope")

        val failure =
            catchThrowableOfType(DataIntegrityViolationException::class.java) {
                logins.login(malformed, presented = null)
            }

        assertThat(failure.mostSpecificCause.message).contains("ck_app_user_email_shape")
        assertThat(IdentityCollision.alreadyRegistered(failure))
            .describedAs("a CHECK violation must never be read as an existing account")
            .isFalse()

        assertThat(users.findAll()).isEmpty()
        assertThat(identities.findAll()).isEmpty()
        assertThat(sessions.findAll()).isEmpty()
    }

    private fun profile(
        subject: String,
        mergeKey: String?,
    ) = ProviderProfile(provider = "GOOGLE", subject = subject, name = "김테스터", mergeKey = mergeKey)

    /**
     * Every profile logs in on its own thread, released together at a barrier. The
     * futures are read with `get`, so a login that failed fails this test carrying
     * its own stack trace rather than being counted as a missing session.
     */
    private fun race(vararg profiles: ProviderProfile): List<SessionToken> {
        val barrier = CyclicBarrier(profiles.size)
        val pool = Executors.newFixedThreadPool(profiles.size)
        try {
            val logins =
                profiles.map { profile ->
                    pool.submit<SessionToken> {
                        barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        this.logins.login(profile, presented = null)
                    }
                }
            return logins.map { attempt ->
                try {
                    attempt.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                } catch (failed: ExecutionException) {
                    throw AssertionError("a concurrent first login failed", failed.cause)
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Runs one login against one rival transaction: [hold] writes the row that will
     * refuse the login's INSERT into [blockedOn], the login is started, and the
     * rival commits only once Postgres reports that INSERT as waiting on a lock.
     */
    private fun stagedRace(
        profile: ProviderProfile,
        blockedOn: String,
        hold: (Connection) -> Unit,
    ): SessionToken {
        val pool = Executors.newSingleThreadExecutor()
        try {
            dataSource.connection.use { rival ->
                rival.autoCommit = false
                hold(rival)
                val login = pool.submit<SessionToken> { logins.login(profile, presented = null) }
                awaitBlockedInsert(blockedOn)
                rival.commit()
                return login.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun Connection.execute(sql: String) = createStatement().use { it.executeUpdate(sql) }

    /**
     * Waits until Postgres itself reports an INSERT into [table] as waiting on a
     * lock, and fails the test if it never does — otherwise a run where the login
     * sailed past the rival transaction would pass while proving nothing.
     */
    private fun awaitBlockedInsert(table: String) {
        val deadline = System.nanoTime() + BLOCK_TIMEOUT.toNanos()
        while (System.nanoTime() < deadline) {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery(
                            "select count(*) from pg_stat_activity " +
                                "where wait_event_type = 'Lock' and query ilike 'insert into $table%'",
                        ).use { rows ->
                            rows.next()
                            if (rows.getInt(1) > 0) return
                        }
                }
            }
            Thread.sleep(POLL_MILLIS)
        }
        throw AssertionError("no insert into $table ever blocked on an index row, so this run raced nothing")
    }

    private companion object {
        const val TIMEOUT_SECONDS = 30L
        const val POLL_MILLIS = 50L
        val BLOCK_TIMEOUT: Duration = Duration.ofSeconds(20)

        @JvmStatic
        @DynamicPropertySource
        fun productionShapedEnvironment(registry: DynamicPropertyRegistry) = SharedPostgres.publish(registry)
    }
}
