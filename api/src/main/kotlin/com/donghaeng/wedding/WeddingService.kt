package com.donghaeng.wedding

import org.springframework.stereotype.Service
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
     * **A second wedding by the same person is allowed** — one person may belong to
     * several, and the screen guides where the API does not refuse.
     *
     * The names are trimmed at this one write point: to this schema `' 김신랑'` and
     * `'김신랑'` are two different names.
     */
    @Transactional
    fun create(
        userId: Long,
        request: CreateWeddingRequest,
    ): WeddingResponse {
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
}
