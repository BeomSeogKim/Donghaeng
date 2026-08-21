package com.donghaeng.wedding

import com.donghaeng.SharedPostgres
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.postgresql.util.PSQLException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.NestedExceptionUtils
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Instant
import java.time.LocalDate

/**
 * **THE TRAP, written before there is anything to pay for.**
 *
 * A wedding is created holding a *live* FREE term, so the first paid term is a
 * **handover** and never an insert: `ux_subscription_live` allows one row per wedding
 * with no `ended_at`, and an implementation that only inserted would fail on the very
 * first real payment (`notes/2026-08-22-decision-entitlement-belongs-to-the-wedding.md`
 * §4, which says in as many words that this is the test to write before the code).
 *
 * `#168` and `#169` are `post-v1` and own the endpoint, the PSP and whatever the gate
 * turns out to refuse. This test owes them one thing: that the day someone writes
 * `terms.save(paidTerm)` and stops, the suite says so.
 *
 * **The plan values are not the subject here.** `plan` has exactly one value on
 * purpose — the free/paid boundary is an open question the founder parked (§7) — so
 * these hand a term over to another `FREE` one and change the payer. What makes it a
 * handover is the term that ENDED, not the word in the column, which is precisely why
 * the mechanism can be built and tested before the pricing exists.
 */
@SpringBootTest
@ActiveProfiles("dev")
internal class SubscriptionTermTest {
    @Autowired private lateinit var weddings: WeddingService

    @Autowired private lateinit var subscriptions: SubscriptionService

    @Autowired private lateinit var terms: WeddingSubscriptionRepository

    @Autowired private lateinit var jdbc: JdbcTemplate

    private var userId: Long = 0

    @BeforeEach
    fun insertUser() {
        userId =
            jdbc.queryForObject(
                "insert into app_user (name, created_at, updated_at) values ('테스터', now(), now()) returning id",
                Long::class.java,
            )!!
    }

    /** FK order, and scoped to this test's own user: the Postgres container is shared with every other class. */
    @AfterEach
    fun clean() {
        jdbc.update("delete from wedding_subscription where wedding_id in (select id from wedding where created_by = ?)", userId)
        jdbc.update("delete from wedding_party where wedding_id in (select id from wedding where created_by = ?)", userId)
        jdbc.update("delete from wedding where created_by = ?", userId)
        jdbc.update("delete from app_user where id = ?", userId)
    }

    @Test
    fun `a plain insert beside the term a wedding was born with is refused by the database`() {
        // The failure this whole class exists for, reached the way an unsuspecting
        // implementation would reach it: open the paid term and leave the free one
        // alone. It is refused by an INDEX and not by a service check, which is what
        // makes it true against two tabs, a double-tapped button and psql alike.
        val wedding = create()

        val now = Instant.now()
        val skipped =
            assertThrows<DataIntegrityViolationException> {
                terms.save(
                    WeddingSubscription(
                        weddingId = wedding.id,
                        plan = SubscriptionPlan.FREE,
                        status = SubscriptionStatus.ACTIVE,
                        payerId = userId,
                        startedAt = now,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            }

        // The protocol field, so this names the index rather than matching a message.
        assertThat((NestedExceptionUtils.getMostSpecificCause(skipped) as PSQLException).serverErrorMessage?.constraint)
            .isEqualTo("ux_subscription_live")
    }

    @Test
    fun `the handover ends the live term and opens the next one, leaving the history`() {
        val wedding = create()
        val born = liveTermOf(wedding.id)
        assertThat(born.plan).isEqualTo(SubscriptionPlan.FREE)
        assertThat(born.payerId).describedAs("nobody paid for a free term").isNull()

        subscriptions.handOver(wedding, SubscriptionPlan.FREE, payerId = userId)

        val all = termsOf(wedding.id)
        assertThat(all).hasSize(2)

        // **The first term is ENDED, not removed and not overwritten.** 신랑이 결제를
        // 하다 끊고 신부가 결제해도 "누가 7월분을 냈나"는 답할 수 있어야 한다 — a mutable
        // payer column would have answered it right up to the day it mattered.
        val ended = all.single { it.id == born.id }
        assertThat(ended.endedAt).isNotNull()
        assertThat(ended.payerId).describedAs("the ended term keeps its own payer").isNull()

        val live = all.single { it.endedAt == null }
        assertThat(live.id).isNotEqualTo(born.id)
        assertThat(live.payerId).isEqualTo(userId)
        // No gap in the timeline: the wedding is entitled at every instant across the
        // handover, which is the whole reason 부부 중 한 명만 결제해도 그 웨딩은 쓸 수 있다.
        assertThat(live.startedAt).isEqualTo(ended.endedAt)
    }

    @Test
    fun `the entitlement is read from the live term, and follows the handover`() {
        // One place reads it — a gate that grew its own `plan` lookup would be a
        // second place, and the two would disagree the first time a term ended.
        val wedding = create()

        assertThat(subscriptions.planOf(wedding)).isEqualTo(SubscriptionPlan.FREE)

        subscriptions.handOver(wedding, SubscriptionPlan.FREE, payerId = userId)

        assertThat(subscriptions.planOf(wedding)).isEqualTo(SubscriptionPlan.FREE)
        assertThat(liveTermOf(wedding.id).payerId).isEqualTo(userId)
    }

    /**
     * A wedding through the service that creates one, so the term under test is the
     * term a real couple gets rather than one this test inserted.
     */
    private fun create(): WeddingScope {
        val created = weddings.create(userId, CreateWeddingRequest(LocalDate.of(2026, 10, 10), WeddingSide.GROOM, "김신랑"))
        return WeddingScope(id = created.id, callerId = userId)
    }

    private fun liveTermOf(weddingId: Long): WeddingSubscription =
        terms.findByWeddingIdAndEndedAtIsNull(weddingId) ?: error("wedding $weddingId holds no live term")

    private fun termsOf(weddingId: Long): List<WeddingSubscription> = terms.findAll().filter { it.weddingId == weddingId }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun productionShapedEnvironment(registry: DynamicPropertyRegistry) = SharedPostgres.publish(registry)
    }
}
