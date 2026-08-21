package com.donghaeng.wedding

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
internal class WeddingService(
    private val weddings: WeddingRepository,
    private val memberships: MembershipRepository,
) {
    /**
     * Creates a wedding **and the creator's membership in it**, in one transaction.
     *
     * The second write is the point of this method rather than bookkeeping beside
     * it: every later request resolves `user → membership → wedding` (`#5`), so half
     * of this committing leaves the couple with a ledger nobody can open — and the
     * onboarding screen runs once, so there is no second attempt that would fix it.
     * [Transactional] is therefore load-bearing and not conventional: Spring Data's
     * per-call transactions would commit each `save` separately.
     * `WeddingPersistenceTest` holds it.
     *
     * **A second wedding by the same person is refused** ([claimSoleMembership]),
     * which reverses a decision this method used to carry: a person belongs to
     * exactly one wedding, created or joined
     * (notes/2026-08-21-decision-one-wedding-per-person.md).
     *
     * The names are trimmed at this one write point: to this schema `' 김신랑'` and
     * `'김신랑'` are two different names.
     */
    @Transactional
    fun create(
        userId: Long,
        request: CreateWeddingRequest,
    ): WeddingResponse {
        // Before the insert, and inside this transaction — see [claimSoleMembership].
        claimSoleMembership(userId)

        val now = Instant.now()
        val wedding =
            weddings.save(
                Wedding(
                    weddingDate = request.weddingDate,
                    groomName = request.groomName.trim(),
                    brideName = request.brideName.trim(),
                    createdBy = userId,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        memberships.save(Membership(weddingId = wedding.id, userId = userId, createdAt = now))
        return wedding.toWeddingResponse()
    }

    /**
     * 한 사람은 웨딩 하나 — **the one place that rule is decided**, and every path that
     * creates a `membership` row calls it first
     * (notes/2026-08-21-decision-one-wedding-per-person.md). `#9`'s invite accept is
     * the second such path and refuses on exactly this call rather than asking the
     * same question its own way: two places deciding whether a person already has a
     * wedding is how they drift, and the second one would arrive without the lock.
     *
     * **The lock is what makes the answer true, not the query.** Reading first and
     * inserting after is a race with a window wide enough for two tabs — measured,
     * not assumed: six simultaneous creates all returned 201 before this existed.
     * [MembershipRepository.lockMembershipSlotOf] closes it for the rest of the
     * caller's transaction, so a second request waits, then sees the committed
     * membership and is refused.
     *
     * **`MANDATORY`, because a lock taken in a transaction of its own is released at
     * once and guards nothing.** The insert has to be inside the same transaction as
     * the check, and this makes a caller that forgot fail loudly instead of quietly
     * re-opening the race. It does not fire for [create]'s own call — self-invocation
     * does not pass through the proxy — which is exactly why it is written for the
     * caller that will arrive from another bean.
     *
     * **The database does not hold this invariant, only this method does.** A partial
     * unique index on `membership (user_id) where deleted_at is null` would make a
     * second live membership unrepresentable; it is not here because every DDL
     * statement against a real database is applied by hand by the founder
     * (notes/2026-08-09-decision-schema-ownership.md), so adding one is their call
     * and not a side effect of this issue.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun claimSoleMembership(userId: Long) {
        memberships.lockMembershipSlotOf(userId)
        if (memberships.existsByUserIdAndDeletedAtIsNull(userId)) throw AlreadyInAWeddingException()
    }

    /**
     * `user → membership → wedding`, or `null` — the walk [CurrentWeddingArgumentResolver]
     * is the gate for. **Every way it can fail returns the same `null`**, so that
     * nothing downstream can accidentally answer a caller differently for a wedding
     * that exists and is not theirs.
     *
     * Two conditions, and neither is redundant. The membership must be live, or a
     * removed partner keeps the ledger they were removed from. The wedding must be
     * live too, because a soft delete does not touch the memberships pointing at it
     * — the partial indexes filter `membership.deleted_at` only — so without the
     * second query a deleted wedding stays fully readable to everyone who was ever
     * in it.
     */
    @Transactional(readOnly = true)
    fun scopeFor(
        callerId: Long,
        weddingId: Long,
    ): WeddingScope? {
        // **The order is load-bearing, not incidental.** Membership first means a
        // non-member and an id nobody owns both cost exactly one indexed lookup and
        // return at the same point; asking about the wedding first would make a
        // stranger's real wedding cost two queries and a nonexistent one cost one,
        // which is the same oracle the 404 exists to close, restated as timing.
        if (!memberships.existsByWeddingIdAndUserIdAndDeletedAtIsNull(weddingId, callerId)) return null
        if (!weddings.existsByIdAndDeletedAtIsNull(weddingId)) return null
        return WeddingScope(id = weddingId, callerId = callerId)
    }

    /**
     * The caller's weddings (`#132`) — the answer to "does this person have one, and
     * which", which a client needs **before** it has an id: `#124` branches the
     * 최초 1회 screen on it and `#15` reloads the ledger with it after a refresh.
     *
     * **A list holding at most one entry** (2026-08-21). It stays a list because the
     * shape is on the seam — `web/` reads `[0]` and generates its types from this
     * response — and narrowing it to a single object would buy nothing while breaking
     * every call site. The "at most one" is [claimSoleMembership]'s, not this query's.
     *
     * An empty list is an ordinary answer here and is not an error — it is precisely
     * the state 최초 1회 is asking about.
     */
    @Transactional(readOnly = true)
    fun list(callerId: Long): List<WeddingResponse> = weddings.findAllLiveForMember(callerId).map { it.toWeddingResponse() }

    /**
     * The wedding itself, for a caller a [WeddingScope] has already been resolved
     * for — so this may not be called with an id that arrived any other way.
     *
     * It re-reads rather than trusting the resolver's row: the two happen in
     * separate transactions, and the wedding can be deleted between them. That race
     * answers 404, which is the same answer the resolver would have given a moment
     * later.
     */
    @Transactional(readOnly = true)
    fun read(weddingId: Long): WeddingResponse =
        weddings.findByIdAndDeletedAtIsNull(weddingId)?.toWeddingResponse() ?: throw WeddingNotFoundException()

    /**
     * 보증인원, for the one caller outside this package that needs it: the headcount
     * (`#151`), which is computed in `guest/` and may not read [Wedding] or
     * [WeddingRepository] itself. **This is that declared read contract**, the cost
     * notes/2026-08-17-decision-first-domain-endpoint-shape.md said the first
     * cross-domain read would have to pay, and paying it is what keeps the two
     * packages' arrow pointing one way
     * (notes/2026-08-21-decision-the-headcount-endpoint.md §3).
     *
     * **Takes the resolved [WeddingScope], never a bare id**, for the reason
     * `HeadcountService.of` does: this is exported across a package boundary, so a
     * `Long` parameter would make "read the 보증인원 of a wedding the caller has no
     * claim on" a mistake that compiles.
     *
     * **`null` is "the couple has not agreed a number with their venue"**, and the
     * caller publishes it by omitting the member rather than by sending a zero. It is
     * also what a wedding deleted between the resolver and this read returns, which
     * needs no separate answer: the number beside it is then a headcount of a ledger
     * nobody can reach, and the next request is a 404 like every other one.
     */
    @Transactional(readOnly = true)
    fun guaranteedHeadcountOf(wedding: WeddingScope): Int? = weddings.findByIdAndDeletedAtIsNull(wedding.id)?.guaranteedHeadcount
}
