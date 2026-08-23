package com.donghaeng.wedding

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * 파트너 초대 (`#181`, the backend half of `#9`) — minting the link that fills a
 * wedding's empty seat, and spending it.
 *
 * **Every rule about the token is either here or in [InviteToken]**, exactly as the
 * session's are in `SessionService` and `SessionToken`, so that no third place has an
 * opinion about how long a link lives or what makes one dead.
 */
@Service
internal class WeddingInviteService(
    private val invites: WeddingInviteRepository,
    private val seats: WeddingSeatRepository,
    private val weddings: WeddingRepository,
    private val weddingService: WeddingService,
) {
    /**
     * 최대 1일 — the founder's call (notes/2026-08-22-decision-the-invite-link.md §1),
     * and **hardcoded rather than a `@ConfigurationProperties`** unlike the session's
     * two lifetimes. A property is a knob the environment outranks (api/AGENTS.md,
     * Schema ownership), so `DONGHAENG_INVITE_LIFETIME=30d` in a deploy platform would
     * extend a bearer credential thirtyfold with the whole suite green. The session's
     * windows are operational; this one is a product decision, and moving it should be
     * a code change somebody reviews.
     */
    private val lifetime: Duration = Duration.ofDays(1)

    /**
     * 초대 링크 발급 — and 재발급, which is the same operation: **there is at most one
     * live invite per seat, so issuing a second one kills the first**
     * (notes/2026-08-22-decision-the-invite-link.md §1). Without that, a couple who taps
     * 재발급 three times holds three live credentials in three places and the one-day
     * life the founder chose is undone by holding several at once.
     *
     * **The seat's row lock is taken first, and it is the whole concurrency design of
     * this file.** Both paths that touch an invite take it before writing one — issue
     * here, [accept] below — so the two can never interleave into a state where a token
     * is minted for a seat somebody has just filled, and two simultaneous 재발급 taps
     * end in one live invite rather than a unique-violation. `ux_wedding_invite_live` is
     * the backstop rather than the plan, the same division `ux_party_user` has with
     * [WeddingService.claimSoleSeat].
     *
     * **A wedding with nobody left to invite is refused** rather than handed a token
     * that could never be spent. `web/` has no 재발급 screen there at all, so this is
     * what a stale tab gets.
     *
     * The caller arrives as a resolved [WeddingScope], so "may this person invite
     * anyone into this wedding" was answered before the method ran: they hold one of
     * its two live seats. Neither seat outranks the other — anything a wedding has
     * hangs off the wedding, never off a seat (root AGENTS.md).
     */
    @Transactional
    fun issue(
        wedding: WeddingScope,
        now: Instant = Instant.now(),
    ): IssuedInviteResponse {
        val waiting = seats.lockWaitingSeats(wedding.id).firstOrNull() ?: throw PartnerAlreadyJoinedException()

        invites.revokeLiveInviteFor(waiting.id, now)

        val token = InviteToken.mint()
        val expiresAt = now.plus(lifetime)
        invites.save(
            WeddingInvite(
                seatId = waiting.id,
                selector = token.selector,
                verifierHash = token.verifierHash,
                issuedBy = wedding.callerId,
                issuedAt = now,
                expiresAt = expiresAt,
            ),
        )
        // The one moment the token exists outside a browser. Nothing logs it, nothing
        // stores it, and no later request can read it back.
        return IssuedInviteResponse(token = token.value, expiresAt = expiresAt)
    }

    /**
     * 초대 수락 화면이 무엇을 수락하는지 보여주기 위한 읽기 (`#214`) — 결혼식 이름,
     * 예식일, 초대한 사람. **It writes nothing and spends nothing**, so a person may
     * open the link, read whose wedding it is, and walk away.
     *
     * **The refusals are [accept]'s, in [accept]'s order, and that is the point.** A
     * screen that could preview a link it cannot use would say 이 결혼식에 참여하시겠어요
     * and then refuse the tap; asking the same questions here means the answer the
     * person reads is the answer they will get. The membership check runs first for the
     * same reason it does there — somebody who already has a wedding is told so rather
     * than shown one they can never join — and it is a plain read rather than
     * [WeddingService.claimSoleSeat], because nothing is being seated and a lock taken
     * for a read guards nothing.
     *
     * **The seat is read, not locked.** A seat somebody fills between this and the
     * accept is exactly the race the accept already answers with
     * [PartnerAlreadyJoinedException]; taking a row lock inside a read-only transaction
     * would serialise every preview against the acceptance it is about, and buy an
     * answer that is stale the moment it is rendered anyway.
     *
     * **What it publishes is argued in [InvitePreviewResponse]** — the token is what
     * stands in for the scope, and there is no wedding id in the answer.
     */
    @Transactional(readOnly = true)
    fun preview(
        callerId: Long,
        request: InvitePreviewRequest,
        now: Instant = Instant.now(),
    ): InvitePreviewResponse {
        if (seats.existsByUserIdAndDeletedAtIsNull(callerId)) throw AlreadyInAWeddingException()

        val invite = liveInviteFor(request.token, now)

        val waiting = seats.findById(invite.seatId).orElse(null) ?: throw InviteNotFoundException()
        // A wedding the couple deleted keeps its seats, so this is the same second
        // condition [accept] and [WeddingService.scopeFor] both carry.
        val wedding = weddings.findByIdAndDeletedAtIsNull(waiting.weddingId) ?: throw InviteNotFoundException()
        if (waiting.userId != null) throw PartnerAlreadyJoinedException()
        // The other live seat: the person who sent the link. `null` would mean a
        // wedding with one seat, which no path of ours can create — and an invite into
        // a wedding we cannot describe is one we refuse to describe.
        val inviter =
            seats.findAllByWeddingIdAndDeletedAtIsNull(waiting.weddingId).firstOrNull { it.id != waiting.id }
                ?: throw InviteNotFoundException()

        return InvitePreviewResponse(
            weddingName = wedding.name,
            weddingDate = wedding.weddingDate,
            invitedBy = inviter.toWeddingSeatResponse(),
        )
    }

    /**
     * The token walk both ends of the link share: parse, find by selector, compare the
     * verifier, then the three deaths.
     *
     * **One copy, because two would drift into two answers for one token** — and the
     * one that drifted would be the read, which is written second and tested less. The
     * comparison order is [accept]'s and is load-bearing: everything wrong with a token
     * answers [InviteNotFoundException] except the two the holder recovers from by
     * asking their partner for the current link, and both of those are asked only
     * AFTER the verifier matched, which is the whole of why saying them is safe
     * (notes/2026-08-22-decision-the-superseded-link-speaks.md).
     */
    private fun liveInviteFor(
        token: String,
        now: Instant,
    ): WeddingInvite {
        val presented = InviteToken.parse(token) ?: throw InviteNotFoundException()
        val invite = invites.findBySelector(presented.selector) ?: throw InviteNotFoundException()
        // The gate: the selector is a public handle and this is the only thing between
        // a guessed one and a stranger's ledger.
        if (!presented.matches(invite.verifierHash)) throw InviteNotFoundException()
        if (invite.wasSpent()) throw InviteNotFoundException()
        if (invite.wasSuperseded()) throw InviteSupersededException()
        if (invite.hasExpiredAt(now)) throw InviteExpiredException()
        return invite
    }

    /**
     * 초대 수락 — **an UPDATE of one identified row**, because both seats exist from
     * the moment the wedding does (notes/2026-08-22-decision-the-couples-two-seats.md
     * §2). That is what makes "two people opened the same link" a lost update rather
     * than a duplicate seat, and it is why the seat is locked below rather than
     * re-checked.
     *
     * **The order of the refusals is the design, not a style.**
     *
     * 1. **한 사람은 웨딩 하나 first, from the same check `POST /weddings` uses**
     *    ([WeddingService.claimSoleSeat], whose `MANDATORY` propagation was written for
     *    this caller — notes/2026-08-21-decision-one-wedding-per-person.md §3). Asking
     *    the same question a second way here is how the two answers drift, and this one
     *    would arrive without the lock. Running it **before** the token is looked at is
     *    what stops somebody who cannot use a link from spending their partner's only
     *    invite by tapping it.
     * 2. **The token, and everything wrong with it answers the same thing** — see
     *    [InviteNotFoundException] — except the two deaths the holder recovers from by
     *    asking their partner for the current link: it went stale
     *    ([InviteExpiredException]) and a 재발급 replaced it
     *    ([InviteSupersededException]). Both are asked **after** the verifier matched,
     *    which is the whole of why saying them is safe.
     * 3. **The seat, locked.** A seat that is gone, or a wedding that is, reads as an
     *    invite that never existed; a seat somebody else has just taken is 409.
     * 4. **The consume, conditional.** A rowcount of 0 means the token died between the
     *    read and the write, which is the same answer as never having existed — and
     *    stays so even though a 재발급 is the likeliest cause: naming that death would
     *    need a second read, which races the same way the first one just did.
     *
     * The response is the wedding itself, so a client that has just joined already has
     * the id every scoped request needs.
     */
    @Transactional
    fun accept(
        callerId: Long,
        request: JoinWeddingRequest,
        now: Instant = Instant.now(),
    ): WeddingResponse {
        weddingService.claimSoleSeat(callerId)

        val invite = liveInviteFor(request.token, now)

        val seat = seats.lockSeat(invite.seatId) ?: throw InviteNotFoundException()
        // A wedding the couple deleted keeps its seats — the partial indexes filter the
        // seat's own `deleted_at` only — so without this an old link would still open a
        // ledger that is gone (the same second condition [WeddingService.scopeFor] has).
        if (!weddings.existsByIdAndDeletedAtIsNull(seat.weddingId)) throw InviteNotFoundException()
        if (seat.userId != null) throw PartnerAlreadyJoinedException()
        if (invites.consume(invite.id, callerId, now) != 1) throw InviteNotFoundException()

        seat.userId = callerId
        // Trimmed at this one write point, exactly as `POST /weddings` trims the other
        // seat's: to this schema `' 이신부'` and `'이신부'` are two different names.
        seat.name = request.name.trim()
        // Not `created_at`: the seat was created with the wedding, and this is the
        // moment it acquired a person.
        seat.joinedAt = now
        seat.updatedAt = now

        return weddingService.read(seat.weddingId)
    }
}
