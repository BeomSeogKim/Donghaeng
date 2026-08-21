package com.donghaeng.wedding

import com.donghaeng.SharedPostgres
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.Instant
import java.time.LocalDate

/**
 * What the rows themselves enforce, against a real Postgres. Two claims the contract
 * test structurally cannot make.
 *
 * **Atomicity**: if the membership does not get written, the wedding must not exist
 * either. `CreateWeddingContractTest` proves both rows appear when nothing goes
 * wrong, which is a different claim — with `@Transactional` deleted it stays green,
 * because Spring Data gives each `save` a transaction of its own and both still
 * commit. So the failure is injected rather than waited for.
 *
 * **The soft-delete filter**: nothing deletes a wedding or a membership yet (`#8`,
 * `#9`), so without this `@SQLRestriction` could be dropped from either entity with
 * the whole suite green — and the miss would surface as a removed partner still
 * reading the ledger. The rows are soft-deleted through JDBC precisely because no
 * domain method exists; what is under test is the mapping.
 */
@SpringBootTest
@ActiveProfiles("dev")
internal class WeddingPersistenceTest {
    @Autowired private lateinit var weddings: WeddingRepository

    @Autowired private lateinit var memberships: MembershipRepository

    @Autowired private lateinit var creations: WeddingService

    @Autowired private lateinit var jdbc: JdbcTemplate

    @MockitoSpyBean private lateinit var membershipRows: MembershipRepository

    /**
     * One `app_user` per test, created here rather than per test method so the
     * cleanup below always has a real id to delete by — a cleanup keyed on a field a
     * test forgot to set deletes nothing and says so to nobody.
     */
    private var userId: Long = 0

    @BeforeEach
    fun insertUser() {
        userId =
            jdbc.queryForObject(
                "insert into app_user (name, created_at, updated_at) values ('테스터', now(), now()) returning id",
                Long::class.java,
            )!!
    }

    /**
     * The container is shared with every other test class and `wedding.created_by`
     * references `app_user`, so a row left behind here makes another class's
     * `users.deleteAll()` fail with a foreign-key violation.
     */
    @AfterEach
    fun clean() {
        jdbc.update("delete from membership where user_id = ?", userId)
        jdbc.update("delete from wedding where created_by = ?", userId)
        jdbc.update("delete from app_user where id = ?", userId)
    }

    @Test
    fun `a wedding whose membership write fails is not left behind`() {
        doThrow(IllegalStateException("the membership write fails"))
            .`when`(membershipRows)
            .save(any(Membership::class.java))

        assertThatThrownBy {
            creations.create(userId, CreateWeddingRequest(LocalDate.of(2026, 10, 10), "김신랑", "이신부"))
        }.isInstanceOf(IllegalStateException::class.java)

        // Read outside the failed transaction, and read natively as well:
        // `@SQLRestriction` would hide a row that survived and was then soft-deleted,
        // and this has to see anything that survived at all.
        assertThat(weddings.findAll()).isEmpty()
        assertThat(jdbc.queryForObject("select count(*) from wedding where created_by = ?", Long::class.java, userId))
            .isZero()
    }

    @Test
    fun `the loser of the race is told what someone who already had a wedding is told`() {
        // The state `ux_membership_user` created (2026-08-21): the check can now be
        // WRONG — two transactions that both read no membership, of which one
        // commits first — and the loser's INSERT is refused by the database instead
        // of succeeding. **That must be the same answer as never having raced at
        // all.** From the caller's side it is one fact ("you already belong to a
        // wedding") with one published recovery, and an untranslated violation is a
        // masked 500 that reads as "we are broken" and invites a retry that can only
        // fail again.
        //
        // The race is SIMULATED rather than run, and deliberately: the advisory lock
        // makes it unreachable over HTTP, which is what
        // `simultaneous creates leave exactly one wedding` asserts. Stubbing the
        // check to keep answering "no membership" is exactly the window the lock
        // closes, so this is the only way to reach the backstop on purpose.
        // AlreadyInAWeddingException is 409 ALREADY_IN_A_WEDDING; that half is
        // `CreateWeddingContractTest`'s.
        doReturn(false).`when`(membershipRows).existsByUserIdAndDeletedAtIsNull(anyLong())

        creations.create(userId, CreateWeddingRequest(LocalDate.of(2026, 10, 10), "김신랑", "이신부"))

        assertThatThrownBy {
            creations.create(userId, CreateWeddingRequest(LocalDate.of(2027, 3, 3), "박신랑", "최신부"))
        }.isInstanceOf(AlreadyInAWeddingException::class.java)

        // And the refusal is still total. The wedding of the losing request is
        // written BEFORE its membership — the foreign key leaves no other order — so
        // a translation that did not let the transaction roll back would leave a
        // ledger nobody can open, which is the failure `@Transactional` on `create`
        // exists for.
        assertThat(weddings.findAll()).hasSize(1)
        assertThat(jdbc.queryForObject("select count(*) from wedding where created_by = ?", Long::class.java, userId)).isOne()
        assertThat(memberships.findAll()).hasSize(1)
    }

    @Test
    fun `a soft-deleted wedding and membership are invisible to the JPA path`() {
        val now = Instant.now()
        val wedding = weddings.save(Wedding(LocalDate.of(2026, 10, 10), "김신랑", "이신부", userId, now, now))
        val membership = memberships.save(Membership(weddingId = wedding.id, userId = userId, createdAt = now))

        assertThat(weddings.findById(wedding.id)).isPresent()
        assertThat(memberships.findById(membership.id)).isPresent()

        jdbc.update("update wedding set deleted_at = now() where id = ?", wedding.id)
        jdbc.update("update membership set deleted_at = now() where id = ?", membership.id)

        // Both rows are still there — soft, not gone — and neither is reachable.
        assertThat(jdbc.queryForObject("select count(*) from wedding where id = ?", Long::class.java, wedding.id)).isOne()
        assertThat(weddings.findById(wedding.id)).isEmpty()
        assertThat(memberships.findById(membership.id)).isEmpty()
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun productionShapedEnvironment(registry: DynamicPropertyRegistry) = SharedPostgres.publish(registry)
    }
}
