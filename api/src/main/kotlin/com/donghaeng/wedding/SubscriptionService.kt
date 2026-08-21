package com.donghaeng.wedding

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * The wedding's entitlement, as a timeline of terms
 * (`notes/2026-08-22-decision-entitlement-belongs-to-the-wedding.md`).
 *
 * Its own service rather than three more methods on [WeddingService], and the split
 * is the one api/AGENTS.md asks for: [WeddingService] answers "whose ledger is this",
 * this answers "what is this ledger entitled to", and the day payment exists the
 * second question grows a PSP, a webhook and a refund path that the first must not
 * grow with it.
 *
 * **Nothing here refuses anything, on purpose.** What free grants is an open question
 * (§7 of that record), so the gate `#169` will build cannot be written yet — a gate
 * before its boundary would be a guess with a 403 on the end of it. What exists now
 * is the term a wedding is born with, the handover that keeps history answerable, and
 * one place that reads the live plan.
 */
@Service
internal class SubscriptionService(
    private val terms: WeddingSubscriptionRepository,
) {
    /**
     * The term every wedding is born holding, written in the same transaction as the
     * wedding and its seats ([WeddingService.create]).
     *
     * **`MANDATORY`, so a caller that forgot the transaction fails loudly.** A
     * wedding that committed without its term would be a wedding whose entitlement
     * cannot be read at all — and, worse, one whose first paid term would then be an
     * ordinary insert, which is exactly the shape [handOver] exists to stop anyone
     * from learning.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun startFreeTerm(
        weddingId: Long,
        at: Instant,
    ) {
        terms.save(
            WeddingSubscription(
                weddingId = weddingId,
                plan = SubscriptionPlan.FREE,
                status = SubscriptionStatus.ACTIVE,
                payerId = null,
                startedAt = at,
                createdAt = at,
                updatedAt = at,
            ),
        )
    }

    /**
     * Ends the live term and opens the next one, in one transaction — **the operation
     * a payment is, and the reason the first payment is not an insert.**
     *
     * A wedding is created holding a live FREE term, so `ux_subscription_live` refuses
     * a second one: an implementation that only inserted would fail on the very first
     * real payment, which is the trap the record says to write a test for before the
     * code. `SubscriptionTermTest` is that test.
     *
     * **Ending first is also what keeps "who paid for July" answerable.** A mutable
     * `payer_id` would answer it right up until the day it mattered — a refund, a
     * dispute, a question from either of them — and this is money.
     *
     * Two things it deliberately does not do. It does not decide `current_period_end`,
     * because what the ended term's money still covers depends on proration and
     * cancellation rules that arrive with a PSP. And it does not translate a lost race
     * into a 409: `endLiveTerm` returning `0` means another handover committed first,
     * and the index then refuses the insert — the caller who must be told about that
     * is `#168`'s endpoint, which does not exist, and a status code invented for
     * nobody is a status code nothing holds.
     */
    @Transactional
    fun handOver(
        wedding: WeddingScope,
        plan: SubscriptionPlan,
        payerId: Long?,
    ) {
        val now = Instant.now()
        terms.endLiveTerm(wedding.id, now)
        terms.save(
            WeddingSubscription(
                weddingId = wedding.id,
                plan = plan,
                status = SubscriptionStatus.ACTIVE,
                payerId = payerId,
                startedAt = now,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    /**
     * What this wedding is entitled to right now — **the one place the entitlement is
     * read**, so that the gate `#169` builds has somewhere to be rather than a
     * `plan` column read from wherever it is convenient.
     *
     * **Takes the resolved [WeddingScope], never a bare id**, for the reason
     * [WeddingService.guaranteedHeadcountOf] does: an entitlement is a fact about one
     * couple's ledger, and a `Long` parameter would make reading a stranger's a
     * mistake that compiles.
     *
     * `null` means no live term, which no wedding this application created can be in.
     * It is returned rather than thrown because what to DO about it is policy — and
     * inventing that policy here is precisely what the open question forbids.
     */
    @Transactional(readOnly = true)
    fun planOf(wedding: WeddingScope): SubscriptionPlan? = terms.findByWeddingIdAndEndedAtIsNull(wedding.id)?.plan
}
