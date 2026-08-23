package com.donghaeng.guest

import com.donghaeng.wedding.WeddingScope
import com.donghaeng.wedding.WeddingSide
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
internal class GuestService(
    private val guests: GuestRepository,
    private val headcounts: HeadcountService,
) {
    /**
     * Writes the party, in the wedding the resolver already proved is the caller's —
     * so [WeddingScope.id] is the only place `wedding_id` can come from.
     *
     * **인원수 3은 하객 세 건이다** (changed 2026-08-23, `#213`,
     * notes/2026-08-23-decision-companions-become-guests.md). Entry is unchanged — the
     * couple still types a number — but the number becomes rows, and the two rules that
     * used to be properties of a count become **defaults applied here**: a companion
     * takes the head's 측, and a head entered 불참 brings its companions in 불참.
     * **Nothing enforces either afterwards**, which is the point of the change: a
     * companion the couple reads as 신부측, and a head who cannot come while their
     * companion still can, are what the count could not say.
     *
     * **The three defaults on the HEAD are applied here and decided in two records.** 참석 is
     * notes/2026-08-06-design-ledger-and-import.md §4, which holds exactly two
     * defaults: that one, and the expected meal count following the party size —
     * `#14`'s, since per-meal-type counts hang off `meal_type` rows only `#10` can
     * create. A party of one and 기타 are
     * notes/2026-08-20-decision-guest-entry-side-and-companions.md §4.
     *
     * **The confirmed slots stay null**, which is the model and not an omission:
     * couple input writes the expected slots only, and a blank confirmed slot means
     * UNKNOWN — never zero, never 불참 (notes/2026-08-03-design-domain-model.md §1).
     *
     * A blank optional field is stored as NULL rather than `''`, and the trim is the
     * rule `WeddingService.create` set: to this schema `' 김영수'` and `'김영수'` are
     * two different names, and the import matcher reads what was stored.
     *
     * No `GuestChange` row. The audit log holds one row per changed FIELD with an old
     * value and a new one, which a creation has none of, and its write path is
     * `#25`'s.
     *
     * **The recomputed 인원수 rides back with the row** (`#151`), read inside this
     * transaction so it already counts the guest just written — 원장과 인원수는 한
     * 화면이고, a client that had to refetch the number would show the ledger and the
     * total disagreeing for one round trip.
     */
    @Transactional
    fun create(
        wedding: WeddingScope,
        request: CreateGuestRequest,
    ): GuestMutationResponse {
        val now = Instant.now()
        val head =
            guests.save(
                Guest(
                    weddingId = wedding.id,
                    name = request.name.trim(),
                    side = request.side,
                    groupCategory = request.groupCategory ?: GuestGroupCategory.OTHER,
                    groupLabel = request.groupLabel.trimmedOrNull(),
                    contact = request.contact.trimmedOrNull(),
                    accessibilityNote = request.accessibilityNote.trimmedOrNull(),
                    expectedAttending = request.expectedAttending ?: true,
                    createdBy = wedding.callerId,
                    createdAt = now,
                    updatedBy = wedding.callerId,
                    updatedAt = now,
                ),
            )
        val companions =
            (1 until (request.expectedPartySize ?: 1)).map { ordinal ->
                guests.save(
                    Guest(
                        weddingId = wedding.id,
                        name = companionName(head.name, ordinal),
                        // The two rules that used to be free, applied ONCE. Nothing
                        // holds them afterwards, and that is the change.
                        side = head.side,
                        expectedAttending = head.expectedAttending,
                        // The group travels too, because the party's people were
                        // already inside the head's group in every aggregation this
                        // ledger has answered; landing them in 기타 would silently move
                        // a couple's breakdown.
                        groupCategory = head.groupCategory,
                        groupLabel = head.groupLabel,
                        // A phone number belongs to a person and a 배려사항 is about a
                        // body. Copying either would invent a fact about somebody we
                        // have been told nothing about.
                        contact = null,
                        accessibilityNote = null,
                        companionOf = head.id,
                        createdBy = wedding.callerId,
                        // The head's clock, so a companion sorts immediately after the
                        // person who brought it and the ledger's entry order is the
                        // order the couple typed.
                        createdAt = now,
                        updatedBy = wedding.callerId,
                        updatedAt = now,
                    ),
                )
            }
        return GuestMutationResponse(
            party = partyOf(id = head.id, name = head.name, members = listOf(head) + companions),
            headcount = headcounts.of(wedding),
        )
    }

    /**
     * `{대표자 이름} 동반 N`, N from 1 — **given once and never regenerated**
     * (notes/2026-08-23-decision-companions-become-guests.md). Renaming the head does
     * not rename these, deleting a sibling does not renumber them, and the moment the
     * couple types over one it is theirs. The generated name exists so the row is
     * addressable at all.
     *
     * **It is truncated by CODE POINT and not by `Char`**, which is the same fact
     * `VisibleCharacters` is written around: a head whose name ends in a
     * supplementary-plane character — an emoji, a CJK Extension B hanja — would
     * otherwise be cut between the two halves of a surrogate pair, and the stored name
     * would end in a replacement character. Reachable only for a head at the 100-character
     * bound, which is exactly the row nobody tests by hand.
     */
    private fun companionName(
        head: String,
        ordinal: Int,
    ): String {
        val suffix = " 동반 $ordinal"
        val room = MAX_NAME_LENGTH - suffix.length
        val headPoints = head.codePointCount(0, head.length)
        val kept = if (headPoints <= room) head else head.substring(0, head.offsetByCodePoints(0, room))
        return kept + suffix
    }

    /**
     * The ledger (`#15`) — the wedding's live 하객, narrowed by 측 and 참석 상태 and by
     * nothing else, oldest first, all of it in one response, **folded into parties**
     * (changed 2026-08-23, `#213`).
     *
     * **The fold is the server's, not the client's**, because the collapsed row states
     * 인원 and a 참석 breakdown and the API returns conclusions rather than rows to add
     * up (api/AGENTS.md, the standing client rule). [GuestPartyResponse] carries what
     * that row reads.
     *
     * **The filters still select GUESTS, and a party appears when any of its members
     * did.** The counts then describe the members that matched — which is what keeps
     * 참석 chip과 식대 인원 the same number for a mixed party, the one case this whole
     * change created.
     *
     * Every one of those is a decision rather than an implementation detail, and
     * they are argued once, in
     * `notes/2026-08-20-decision-the-ledger-read-and-its-filters.md`: no page, entry
     * order as contract, 그룹 excluded on purpose, and what the attendance filter
     * commits `#17` to. **A third filter, or a page, is a change to that record
     * first.**
     *
     * No aggregate rides along: this is a read, and the headcount has its own
     * endpoint ([HeadcountController.read] — the envelope record binds mutations, not
     * this). The screen opens both.
     */
    @Transactional(readOnly = true)
    fun list(
        wedding: WeddingScope,
        side: WeddingSide?,
        attendance: AttendanceFilter?,
    ): List<GuestPartyResponse> {
        val matched =
            guests.findLedger(
                weddingId = wedding.id,
                // An absent filter is every value rather than a null parameter, for
                // the reason GuestRepository.findLedger gives. Both sets are
                // non-empty by construction, which is what that query relies on.
                sides = side?.let(::setOf) ?: WeddingSide.entries.toSet(),
                attendance = attendance?.let { setOf(it.attending) } ?: setOf(true, false),
            )
        // `groupBy` keeps first-encounter order, and the query is ordered by
        // (created_at, id) — a companion carries its head's `created_at` and a larger
        // id, so the parties come out in the order the couple entered the heads, which
        // is the order `docs/api-spec.md` publishes.
        val parties = matched.groupBy { it.companionOf ?: it.id }
        val names = headNames(wedding.id, parties)

        return parties.map { (headId, members) ->
            partyOf(
                id = headId,
                // The head's own row when the filter kept it, the second query's copy
                // when it did not, and — only for a head deleted by hand, which no
                // endpoint can do — the first member's name, so a row always has one.
                name = members.firstOrNull { it.id == headId }?.name ?: names[headId] ?: members.first().name,
                members = members,
            )
        }
    }

    /**
     * The names of party heads the filter EXCLUDED, and nothing else: 대표자는 불참인데
     * 동반은 참석인 팀 under `?attendance=ATTENDING` is a row whose heading is not among
     * the rows that matched.
     *
     * **The second query runs only when a filter actually split a party**, which is
     * never on the ledger's ordinary unfiltered read.
     */
    private fun headNames(
        weddingId: Long,
        parties: Map<Long, List<Guest>>,
    ): Map<Long, String> {
        val missing = parties.filterValues { members -> members.none { it.companionOf == null } }.keys
        if (missing.isEmpty()) return emptyMap()
        return guests.findAllInWedding(weddingId, missing).associate { it.id to it.name }
    }

    private fun String?.trimmedOrNull(): String? = this?.trim()?.ifEmpty { null }

    private companion object {
        /** `guest.name` is a `varchar(100)`, and a generated companion name has to fit it too. */
        private const val MAX_NAME_LENGTH = 100
    }
}
