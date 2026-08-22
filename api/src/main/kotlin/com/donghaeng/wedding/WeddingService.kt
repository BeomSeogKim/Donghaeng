package com.donghaeng.wedding

import com.donghaeng.json.Patch
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
internal class WeddingService(
    private val weddings: WeddingRepository,
    private val seats: WeddingSeatRepository,
    private val subscriptions: SubscriptionService,
) {
    /**
     * Creates a wedding, **both of its seats**, and the free subscription term it is
     * born holding — in one transaction.
     *
     * The writes beside the wedding are the point of this method rather than
     * bookkeeping around it. Every later request resolves `user → seat → wedding`
     * (`#5`), so half of this committing leaves the couple with a ledger nobody can
     * open — and the onboarding screen runs once, so there is no second attempt that
     * would fix it. [Transactional] is therefore load-bearing and not conventional:
     * Spring Data's per-call transactions would commit each `save` separately.
     * `WeddingPersistenceTest` holds it.
     *
     * **Both seats, and the empty one is deliberate**
     * (notes/2026-08-22-decision-the-couples-two-seats.md §2). The alternative —
     * create the partner's seat when they accept — makes `#9`'s invite point at a row
     * that does not exist yet, so the token would have to carry the side itself and
     * re-derive on acceptance what the wedding already knew. Creating both makes the
     * invite an UPDATE of one identified row, which is also what turns "two people
     * accepting the same link" into a lost update rather than a duplicate seat. It is
     * what makes the invariant total, too: `ux_party_wedding_side` enforces at most
     * one seat per side, and this transaction is what supplies "at least".
     *
     * **The free term is written here for the same reason the seats are**: a wedding
     * with no live term is a wedding whose entitlement cannot be read, and a wedding
     * born holding one is what makes the first payment a handover instead of an
     * insert (notes/2026-08-22-decision-entitlement-belongs-to-the-wedding.md §4).
     *
     * **A second wedding by the same person is refused** ([claimSoleSeat]): a person
     * belongs to exactly one wedding, created or joined
     * (notes/2026-08-21-decision-one-wedding-per-person.md).
     *
     * The name is trimmed at this one write point: to this schema `' 김신랑'` and
     * `'김신랑'` are two different names.
     */
    @Transactional
    fun create(
        userId: Long,
        request: CreateWeddingRequest,
    ): WeddingResponse {
        // Before the insert, and inside this transaction — see [claimSoleSeat].
        claimSoleSeat(userId)

        val now = Instant.now()
        val wedding =
            weddings.save(
                Wedding(
                    weddingDate = request.weddingDate,
                    createdBy = userId,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        val taken =
            saveSeatOrRefuse(
                WeddingSeat(
                    weddingId = wedding.id,
                    side = request.side,
                    name = request.name.trim(),
                    userId = userId,
                    joinedAt = now,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        // No name, no user, no joined_at: the partner has not arrived, and nobody
        // types anybody else's name.
        val waiting =
            seats.save(
                WeddingSeat(
                    weddingId = wedding.id,
                    side = partnerOf(request.side),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        subscriptions.startFreeTerm(wedding.id, now)
        return wedding.toWeddingResponse(listOf(taken, waiting))
    }

    /** A wedding has exactly two sides, so the partner's is simply the other one. */
    private fun partnerOf(side: WeddingSide): WeddingSide = if (side == WeddingSide.GROOM) WeddingSide.BRIDE else WeddingSide.GROOM

    /**
     * The caller's seat insert, with the one failure it can now suffer answered in
     * the caller's own words.
     *
     * `ux_party_user` holds 한 사람은 웨딩 하나 in the database as of 2026-08-21, so the
     * loser of a race no longer gets a second seat — it gets a rejected INSERT.
     * **That must read as the same 409 as arriving with a wedding already in hand**:
     * to the caller it is one fact, and the published recovery is one recovery
     * (`docs/api-spec.md`, `POST /weddings`). A masked 500 would say "we are broken"
     * and invite a retry that can only fail again.
     *
     * **Only the caller's seat goes through this.** The partner's carries no
     * `user_id`, so it cannot collide in an index keyed on one.
     *
     * Reaching here means [claimSoleSeat]'s lock was not held for this user — a
     * writer outside this application, or a `#9`-era path that forgot the check.
     * Rare by design, correct anyway.
     */
    private fun saveSeatOrRefuse(seat: WeddingSeat): WeddingSeat {
        try {
            return seats.save(seat)
        } catch (collision: DataIntegrityViolationException) {
            if (!SoleSeatCollision.slotAlreadyTaken(collision)) throw collision
            throw AlreadyInAWeddingException()
        }
    }

    /**
     * 한 사람은 웨딩 하나 — **the one place that rule is decided**, and every path that
     * gives a person a seat calls it first
     * (notes/2026-08-21-decision-one-wedding-per-person.md, whose §2–§5 the seat
     * inherited unchanged on 2026-08-22). `#9`'s invite accept is the second such path
     * and refuses on exactly this call rather than asking the same question its own
     * way: two places deciding whether a person already has a wedding is how they
     * drift, and the second one would arrive without the lock.
     *
     * **The lock is what makes the answer true, not the query.** Reading first and
     * inserting after is a race with a window wide enough for two tabs — measured,
     * not assumed: six simultaneous creates all returned 201 before this existed.
     * [WeddingSeatRepository.lockSeatSlotOf] closes it for the rest of the caller's
     * transaction, so a second request waits, then sees the committed seat and is
     * refused.
     *
     * **`MANDATORY`, because a lock taken in a transaction of its own is released at
     * once and guards nothing.** The insert has to be inside the same transaction as
     * the check, and this makes a caller that forgot fail loudly instead of quietly
     * re-opening the race. It does not fire for [create]'s own call — self-invocation
     * does not pass through the proxy — which is exactly why it is written for the
     * caller that will arrive from another bean.
     *
     * **The database holds this invariant too, and neither half is spare**
     * (`ux_party_user`). The index decides what may EXIST — it is the last word, and
     * it holds against psql as well as against us. This method decides what the API
     * ANSWERS: it is what makes the second request a plain 409 read off a committed
     * row, instead of an INSERT that fails and has to be translated back into one
     * ([saveSeatOrRefuse], the backstop rather than the plan). Delete the lock and
     * every double-tap takes the exceptional path.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun claimSoleSeat(userId: Long) {
        seats.lockSeatSlotOf(userId)
        if (seats.existsByUserIdAndDeletedAtIsNull(userId)) throw AlreadyInAWeddingException()
    }

    /**
     * `user → seat → wedding`, or `null` — the walk [CurrentWeddingArgumentResolver]
     * is the gate for. **Every way it can fail returns the same `null`**, so that
     * nothing downstream can accidentally answer a caller differently for a wedding
     * that exists and is not theirs.
     *
     * Two conditions, and neither is redundant. The seat must be live, or a partner
     * whose seat was released keeps the ledger they were removed from. The wedding
     * must be live too, because a soft delete does not touch the seats pointing at it
     * — the partial indexes filter `wedding_party.deleted_at` only — so without the
     * second query a deleted wedding stays fully readable to everyone who ever sat in
     * it.
     */
    @Transactional(readOnly = true)
    fun scopeFor(
        callerId: Long,
        weddingId: Long,
    ): WeddingScope? {
        // **The order is load-bearing, not incidental.** The seat first means a
        // non-member and an id nobody owns both cost exactly one indexed lookup and
        // return at the same point; asking about the wedding first would make a
        // stranger's real wedding cost two queries and a nonexistent one cost one,
        // which is the same oracle the 404 exists to close, restated as timing.
        if (!seats.existsByWeddingIdAndUserIdAndDeletedAtIsNull(weddingId, callerId)) return null
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
     * every call site. The "at most one" is [claimSoleSeat]'s, not this query's.
     *
     * An empty list is an ordinary answer here and is not an error — it is precisely
     * the state 최초 1회 is asking about.
     *
     * The seats are read per wedding rather than joined in, which is an N+1 over a
     * list of at most one row: the alternative is a fetch join whose duplicate-row
     * handling would have to be written and read for a query that can return two rows
     * in total.
     */
    @Transactional(readOnly = true)
    fun list(callerId: Long): List<WeddingResponse> = weddings.findAllLiveForMember(callerId).map { it.toWeddingResponse(seatsOf(it.id)) }

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
        weddings.findByIdAndDeletedAtIsNull(weddingId)?.toWeddingResponse(seatsOf(weddingId)) ?: throw WeddingNotFoundException()

    /**
     * 웨딩 정보 수정 (`#173`, the backend half of `#8`) — 예식일 and 보증인원, the two
     * things the wedding row holds that a couple can change. **The names are not
     * here**: after 2026-08-22 a name belongs to a seat, and `#175` edits one.
     *
     * **A member the caller did not send is not written**, which is the whole of
     * [UpdateWeddingRequest]'s [Patch] and is what makes this an edit rather than a
     * replacement (notes/2026-08-22-decision-partial-update-shape.md). Two partners
     * with the same screen open therefore collide only on a field they both typed
     * into — and `@DynamicUpdate` is what carries that all the way down to the UPDATE
     * statement, since `wedding` has no `guest_change` trail to recover an
     * overwritten value from
     * (notes/2026-08-20-decision-row-concurrency-and-the-audit-trail.md).
     *
     * **`updatedAt` moves only if a value actually changed.** The clock is the
     * service's, as everywhere here, and a row reported as touched when it was not is
     * a lie an audit read would believe — which is as true of a form resubmitted
     * unedited as it is of an empty body, so both are compared rather than assumed.
     *
     * It re-reads the row rather than trusting the resolver's, for the reason [read]
     * does: the two are separate transactions and the wedding can be deleted between
     * them, which answers 404 exactly as the resolver would have a moment later.
     *
     * **This returns the wedding alone, not the mutation envelope.** The recomputed
     * 인원수 that response also carries is a fold over the ledger, which lives on the
     * far side of an arrow this package may not point back along; `guest/`'s
     * `WeddingUpdateService` is the one transaction both happen in
     * (notes/2026-08-21-decision-the-headcount-endpoint.md §3).
     */
    @Transactional
    fun update(
        wedding: WeddingScope,
        request: UpdateWeddingRequest,
    ): WeddingResponse {
        val row = weddings.findByIdAndDeletedAtIsNull(wedding.id) ?: throw WeddingNotFoundException()
        var written = false

        when (val date = request.weddingDate) {
            // Compared, not just assigned: a member resent unchanged is a member
            // nothing happened to, and `updatedAt` below is the only thing that would
            // record otherwise (Hibernate's own dirty checking is by value too, so an
            // equal assignment emits no UPDATE of its own).
            is Patch.Set ->
                if (row.weddingDate != date.value) {
                    row.weddingDate = date.value
                    written = true
                }
            // Absent leaves it alone. Cleared cannot arrive — `@NotCleared` refuses
            // it with a 400, because a wedding always has a date, and
            // `PatchMemberSweepTest` is what keeps that annotation from being forgotten.
            Patch.Absent, Patch.Cleared -> Unit
        }
        when (val guaranteed = request.guaranteedHeadcount) {
            is Patch.Set ->
                if (row.guaranteedHeadcount != guaranteed.value) {
                    row.guaranteedHeadcount = guaranteed.value
                    written = true
                }
            // 계약이 바뀐 커플은 미설정으로 돌아간다: the venue's number is theirs to
            // withdraw, and we never hold a stale one in the gap. Clearing a column
            // that is already NULL is not a change either.
            Patch.Cleared ->
                if (row.guaranteedHeadcount != null) {
                    row.guaranteedHeadcount = null
                    written = true
                }
            Patch.Absent -> Unit
        }

        if (written) row.updatedAt = Instant.now()
        return row.toWeddingResponse(seatsOf(wedding.id))
    }

    /**
     * 본인 이름 수정 (`#187`, the backend half of `#175`) — **the caller's own seat, and
     * the only row this endpoint can reach.**
     *
     * A name entered in exactly two places before this existed, `POST /weddings` and
     * `POST /weddings/join`, and both are once-only — so a typo was permanent in a
     * value the ledger header renders on every screen.
     *
     * **The seat is looked up by `(weddingId, callerId)`, which is what keeps 아무도
     * 남의 이름을 대신 적지 않는다 total** (notes/2026-08-22-decision-the-couples-two-seats.md).
     * The partner's row is not refused here; it cannot be named — the request carries
     * no seat id and no `side`, so pre-filling an empty seat before its person arrives
     * is unrepresentable rather than validated against
     * (notes/2026-08-22-decision-the-seat-name-edit.md §2).
     *
     * Both rows are re-read rather than taken from the resolver, for the reason [read]
     * gives: those are separate transactions, and a wedding deleted in between answers
     * 404 exactly as the resolver would have a moment later. The wedding is checked as
     * well as the seat because a soft-deleted wedding keeps its seats — the partial
     * indexes filter the seat's own `deleted_at` only.
     *
     * **`updatedAt` moves only if the name actually changed**, the rule [update]
     * follows: a row reported as touched when it was not is a lie an audit read would
     * believe, and a form resubmitted unedited is the ordinary way that happens.
     *
     * Trimmed here, as the two write points that enter a name trim: to this schema
     * `' 김신랑'` and `'김신랑'` are two different names.
     *
     * **No lock and no `@Version`**: the row is the caller's own, the payload is
     * absolute, and `@DynamicUpdate` on [WeddingSeat] keeps the statement to the
     * column that changed
     * (notes/2026-08-20-decision-row-concurrency-and-the-audit-trail.md).
     *
     * **It answers the wedding and no aggregate.** 인원수 is a fold over the ledger and
     * a seat's name is not in it, so there is no recomputed number — the same answer
     * `POST /weddings/join` gives after writing this very column, and the reason this
     * write stays in `wedding/` rather than being assembled in `guest/` the way
     * `PATCH /weddings/{weddingId}` is.
     */
    @Transactional
    fun renameSeat(
        wedding: WeddingScope,
        request: UpdateSeatNameRequest,
    ): WeddingResponse {
        val row = weddings.findByIdAndDeletedAtIsNull(wedding.id) ?: throw WeddingNotFoundException()
        val seat =
            seats.findByWeddingIdAndUserIdAndDeletedAtIsNull(wedding.id, wedding.callerId) ?: throw WeddingNotFoundException()

        val name = request.name.trim()
        if (seat.name != name) {
            seat.name = name
            seat.updatedAt = Instant.now()
        }
        return row.toWeddingResponse(seatsOf(wedding.id))
    }

    private fun seatsOf(weddingId: Long): List<WeddingSeat> = seats.findAllByWeddingIdAndDeletedAtIsNull(weddingId)

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
