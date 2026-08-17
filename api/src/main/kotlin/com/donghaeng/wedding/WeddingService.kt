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
}
