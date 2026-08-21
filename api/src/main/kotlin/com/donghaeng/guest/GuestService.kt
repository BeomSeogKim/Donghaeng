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
     * Writes one ledger row, in the wedding the resolver already proved is the
     * caller's — so [WeddingScope.id] is the only place `wedding_id` can come from.
     *
     * **The three defaults are applied here and decided in two records.** 참석 is
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
        val guest =
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
                    expectedPartySize = request.expectedPartySize ?: 1,
                    createdBy = wedding.callerId,
                    createdAt = now,
                    updatedBy = wedding.callerId,
                    updatedAt = now,
                ),
            )
        return guest.toGuestMutationResponse(headcounts.of(wedding))
    }

    /**
     * The ledger (`#15`) — the wedding's live 하객, narrowed by 측 and 참석 상태 and by
     * nothing else, oldest first, all of it in one response.
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
    ): List<GuestResponse> =
        guests
            .findLedger(
                weddingId = wedding.id,
                // An absent filter is every value rather than a null parameter, for
                // the reason GuestRepository.findLedger gives. Both sets are
                // non-empty by construction, which is what that query relies on.
                sides = side?.let(::setOf) ?: WeddingSide.entries.toSet(),
                attendance = attendance?.let { setOf(it.attending) } ?: setOf(true, false),
            ).map { it.toGuestResponse() }

    private fun String?.trimmedOrNull(): String? = this?.trim()?.ifEmpty { null }
}
