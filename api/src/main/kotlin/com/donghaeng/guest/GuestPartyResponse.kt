package com.donghaeng.guest

import io.swagger.v3.oas.annotations.media.Schema

/**
 * **한 팀이 원장의 한 줄이다** — the folded row the ledger screen draws, and what
 * `GET /weddings/{weddingId}/guests` is a list of since 2026-08-23 (`#213`,
 * notes/2026-08-23-decision-companions-become-guests.md).
 *
 * A party is a head guest and everyone they brought. The screen shows one row per
 * party with a disclosure on any party of two or more; expanding shows [members],
 * each holding its own 참석.
 *
 * **The three counts the collapsed row needs are answered here, not derived there**
 * — the standing client rule is that the API returns conclusions rather than rows to
 * compute over (api/AGENTS.md). [size] and [attendingCount] are what the 참석 column
 * reads:
 *
 * | | what the row says |
 * |---|---|
 * | `attendingCount == size` | 참석 |
 * | `attendingCount == 0` | 불참 |
 * | anything between | **`3 / 4`**, and pressing it expands rather than picking one |
 *
 * That third state is 애매한 것은 추측하지 않는다 applied to a control instead of to
 * an import: a mixed party has no attendance, so the screen states the count it does
 * know and hands the decision back.
 *
 * **[id] and [name] are the HEAD's**, because the party's identity is the person the
 * couple entered. They are published beside [members] rather than inside it so that a
 * filtered read still has a row heading: under `?attendance=ATTENDING` a party whose
 * head is 불참 appears with the head's name and only the members that matched — and
 * [size] counts THOSE, so 참석 chip과 식대 인원은 계속 같은 숫자를 말한다.
 *
 * **Members are never counted twice**: [members] is the whole of what this row
 * carries, head included when the head matched. Do not add [name] to it.
 */
data class GuestPartyResponse(
    @param:Schema(description = "The head guest's id — the party's identity, and what a client keys a row on")
    val id: Long,
    @param:Schema(description = "The head guest's name — what the collapsed row reads", example = "김영수")
    val name: String,
    @param:Schema(description = "How many people this row carries: the members below, after any filter", example = "4")
    val size: Int,
    @param:Schema(description = "How many of them are 참석. Equal to `size`, zero, or something between", example = "3")
    val attendingCount: Int,
    @param:Schema(description = "The people, in entry order, head first when it is here")
    val members: List<GuestResponse>,
)

/**
 * 참석 여부 — **the confirmed answer when there is one, the couple's expected value
 * otherwise**, which is the ledger's one attendance axis
 * (notes/2026-08-05-design-meal-headcount.md §1).
 *
 * **It is the Kotlin twin of a `coalesce` two queries also spell**
 * ([GuestRepository.findLedger] filters on it, [GuestRepository.countAttending] counts
 * it), and one axis read two ways is exactly how a chip and a number drift apart. What
 * stops that here is not care: `HeadcountContractTest` asserts that the parties under
 * `?attendance=ATTENDING` carry the same total the headcount answers, so a divergence
 * between this function and that SQL is red rather than quiet.
 *
 * Nothing in v1 writes a confirmed value
 * (notes/2026-08-21-decision-attendance-is-two-states.md), so today this is exactly
 * [Guest.expectedAttending] — and it will not be the day `#23` lands.
 *
 * **It lives in this file rather than beside the entity** because a top-level function
 * in `Guest.kt` compiles to a `GuestKt` facade that `ArchitectureTest` places in no
 * layer, and an unplaced class may read no row. A response file's facade is the
 * service layer by that test's own definition, which is where reading a row belongs.
 */
private fun Guest.isAttending(): Boolean = confirmedAttending ?: expectedAttending

/**
 * The one place a party is assembled, so that the create and the ledger read cannot
 * disagree about what [GuestPartyResponse.size] counts.
 *
 * [id] and [name] are passed rather than read off the members, because the ledger read
 * has a case where the head is not among them — see [GuestService.list].
 */
internal fun partyOf(
    id: Long,
    name: String,
    members: List<Guest>,
) = GuestPartyResponse(
    id = id,
    name = name,
    size = members.size,
    attendingCount = members.count { it.isAttending() },
    members = members.map { it.toGuestResponse() },
)
