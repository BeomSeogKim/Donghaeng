package com.donghaeng.auth.account

import com.donghaeng.SharedPostgres
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * THE RED GATE OF `#94`'s FIRST HALF: what a returning login is allowed to change
 * about the row it just recognised.
 *
 * The requirement is one sentence — a display name changed at the provider must
 * reach us — and three of the four tests below are about what must NOT happen on
 * the way there. A login is the most frequent write path this application will
 * ever have, and it is the one path that touches the row the account merge key
 * lives on, so "refresh the profile" is bounded from both sides:
 *
 * - **no write at all when nothing changed**, asserted on `xmin` rather than on the
 *   value, because a blind `set name = :name` leaves every value assertion green;
 * - **the address and its verifier are never touched**, which is the half `#94`
 *   explicitly withholds until `#110` (2026-08-13 record, `#94` §2). The profile
 *   here carries a merge key that does NOT match the stored address, so an update
 *   that reached one column too far fails rather than passes quietly.
 *
 * Testcontainers, and it has to be: `is distinct from`, `xmin`, and the fact that a
 * zero-row UPDATE writes no tuple are all statements about Postgres.
 */
@SpringBootTest
@ActiveProfiles("dev")
internal class ProfileRefreshTest {
    @Autowired private lateinit var refreshes: ProfileRefreshService

    @Autowired private lateinit var logins: LoginService

    @Autowired private lateinit var users: AppUserRepository

    @Autowired private lateinit var identities: OauthIdentityRepository

    @Autowired private lateinit var jdbc: JdbcTemplate

    /**
     * One test instance, one identity and one address. Both unique indexes are real
     * here and rows outlive the test that wrote them, so a fixed subject or a fixed
     * address would make these tests each other's problem.
     */
    private val unique = FIXTURES.incrementAndGet()
    private val email = "kim-$unique@gmail.com"
    private val subject = "google-subject-$unique"

    @Test
    fun `a name changed at the provider reaches the stored row`() {
        val id = existingUser(name = "김테스터")

        refreshes.refresh(id, profile(name = "김바뀜"), LATER)

        assertThat(nameOf(id)).isEqualTo("김바뀜")
        assertThat(updatedAtOf(id)).isEqualTo(LATER)
    }

    @Test
    fun `a name we never had is learned rather than left null`() {
        // The provider that returned nothing last time and something this time. It
        // is the same statement as the one above only if `is distinct from` is what
        // decides — `name <> :name` is NULL here, and this row would stay nameless
        // forever.
        val id = existingUser(name = null)

        refreshes.refresh(id, profile(name = "김테스터"), LATER)

        assertThat(nameOf(id)).isEqualTo("김테스터")
    }

    @Test
    fun `an unchanged name performs no write at all`() {
        val id = existingUser(name = "김테스터")
        val before = versionOf(id)

        refreshes.refresh(id, profile(name = "김테스터"), LATER)

        // `xmin` is the transaction that wrote the row's current version. An UPDATE
        // that sets a column to the value it already holds still writes a NEW tuple
        // and a new `xmin` — so this is the assertion a blind update fails, where
        // every assertion about the NAME would pass. Every login is this path.
        assertThat(versionOf(id)).describedAs("the row was rewritten with the value it already held").isEqualTo(before)
        assertThat(updatedAtOf(id)).isEqualTo(EARLIER)
    }

    @Test
    fun `a provider that sends no name does not blank the one we have`() {
        // An absent field is not an instruction to erase. Kakao returns the nickname
        // only when that consent item was granted, so this arrives at `#89` as a
        // person's name vanishing on a login they did nothing differently in.
        val id = existingUser(name = "김테스터")
        val before = versionOf(id)

        refreshes.refresh(id, profile(name = null), LATER)
        refreshes.refresh(id, profile(name = "   "), LATER)

        assertThat(nameOf(id)).isEqualTo("김테스터")
        assertThat(versionOf(id)).isEqualTo(before)
    }

    @Test
    fun `the merge key and its verifier are never written here`() {
        // `#94`'s second half — giving an account a verified address it never had —
        // is NOT this change and cannot be reached from it (`#110`). The profile
        // below carries a merge key that disagrees with the stored address in every
        // way that matters, so an update that also wrote `email` would land a
        // provider-supplied address on someone else's row and this would go red.
        val id = existingUser(name = "김테스터")

        refreshes.refresh(id, profile(name = "김바뀜", mergeKey = "attacker@gmail.com"), LATER)

        assertThat(columnOf(id, "email")).isEqualTo(email)
        assertThat(columnOf(id, "email_verified_by")).isEqualTo("GOOGLE")
    }

    @Test
    fun `an account with no address does not acquire one here`() {
        // THE ROW `#110` WILL BE TEMPTED BY, and the one the test above cannot see:
        // the person who signed up with Kakao, has no verified address, and now
        // arrives with a Google login carrying one. `set email = coalesce(email,
        // :mergeKey)` would look like a kindness and would pass every other
        // assertion in this file.
        //
        // It is a takeover. A merge key is a claim that someone checked mailbox
        // control, and the address only becomes one after WE verify it (2026-08-13
        // record, `#94` §2) — while `#93`'s retry on `ux_app_user_email` seats a
        // colliding login on whichever row already holds the address, which is what
        // makes an unverified value there a way into a stranger's ledger.
        val id = addresslessUser()

        refreshes.refresh(id, profile(name = "김바뀜", mergeKey = "victim@gmail.com"), LATER)

        assertThat(nameOf(id)).isEqualTo("김바뀜")
        assertThat(columnOf(id, "email")).isNull()
        assertThat(columnOf(id, "email_verified_by")).isNull()
    }

    @Test
    fun `a name the database refuses costs the person their name, never their login`() {
        // A NUL byte is the realistic one: `truncateName` already bounds the length,
        // and nothing bounds the bytes. Refused, it would otherwise 500 this
        // person's EVERY login from now on — the same permanent lockout for a
        // display name that GoogleProfile refuses to accept one property over.
        val id = existingUser(name = "김테스터")
        val before = versionOf(id)

        refreshes.refresh(id, profile(name = "김\u0000테스터"), LATER)

        assertThat(nameOf(id)).isEqualTo("김테스터")
        assertThat(versionOf(id)).isEqualTo(before)

        // And the failure did not poison anything: the next ordinary refresh works.
        refreshes.refresh(id, profile(name = "김바뀜"), LATER)
        assertThat(nameOf(id)).isEqualTo("김바뀜")
    }

    @Test
    fun `a login whose refresh fails still issues a session`() {
        // The same failure through the REAL call site, which is where the claim
        // actually has to hold. It is asserted here rather than over HTTP because a
        // NUL cannot be carried through the stub provider's signed ID token — that
        // login fails upstream of anything this change owns, and a test built on it
        // would pass for the wrong reason.
        logins.login(profile(name = "김테스터"), presented = null, now = EARLIER)
        val id = identities.findByProviderAndProviderUserId("GOOGLE", subject)!!.userId

        val session = logins.login(profile(name = "김\u0000바뀜"), presented = null, now = LATER)

        assertThat(session).isNotNull()
        assertThat(nameOf(id)).isEqualTo("김테스터")
    }

    /**
     * Stored with an address, because the assertions that matter are about the row
     * NOT changing around the name.
     */
    private fun existingUser(name: String?): Long =
        users
            .save(
                AppUser(
                    email = email,
                    emailVerifiedBy = "GOOGLE",
                    name = name,
                    createdAt = EARLIER,
                    updatedAt = EARLIER,
                ),
            ).id

    /**
     * The Kakao-then-Google account of the 2026-08-13 record: a real person, a real
     * row, and no address at all. `ck_app_user_email_verified_by` makes the pair
     * total, so no verifier either.
     */
    private fun addresslessUser(): Long = users.save(AppUser(name = "김테스터", createdAt = EARLIER, updatedAt = EARLIER)).id

    private fun profile(
        name: String?,
        mergeKey: String? = null,
    ) = ProviderProfile(provider = "GOOGLE", subject = subject, name = name, mergeKey = mergeKey)

    /** Through JDBC, never through the repository: a cached entity proves nothing. */
    private fun columnOf(
        id: Long,
        column: String,
    ): String? = jdbc.queryForObject("select $column from app_user where id = ?", String::class.java, id)

    private fun nameOf(id: Long): String? = columnOf(id, "name")

    private fun updatedAtOf(id: Long): Instant =
        jdbc.queryForObject("select updated_at from app_user where id = ?", Timestamp::class.java, id)!!.toInstant()

    private fun versionOf(id: Long): String = columnOf(id, "xmin::text")!!

    private companion object {
        /** Whole microseconds, which is all `timestamptz` keeps. */
        val EARLIER: Instant = Instant.parse("2026-08-01T00:00:00Z")
        val LATER: Instant = Instant.parse("2026-08-17T09:30:00Z")

        val FIXTURES = AtomicInteger()

        @JvmStatic
        @DynamicPropertySource
        fun productionShapedEnvironment(registry: DynamicPropertyRegistry) = SharedPostgres.publish(registry)
    }
}
