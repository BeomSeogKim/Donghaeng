package com.donghaeng.guest

import com.donghaeng.wedding.WeddingScope
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
internal class GuestService(
    private val guests: GuestRepository,
) {
    /**
     * Writes one ledger row, in the wedding the resolver already proved is the
     * caller's — so [WeddingScope.id] is the only place `wedding_id` can come from.
     *
     * **Of the three defaults, one is recorded and two are not.** 참석 is
     * notes/2026-08-06-design-ledger-and-import.md §4, which holds exactly two
     * defaults: that one, and the expected meal count following the party size —
     * `#14`'s, since per-meal-type counts hang off `meal_type` rows only `#10` can
     * create. **A party of one and 기타 are in no record**: they are stated in
     * `docs/api-spec.md` and nowhere else, and are decisions being made here rather
     * than applied from one.
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
        return guest.toGuestMutationResponse()
    }

    private fun String?.trimmedOrNull(): String? = this?.trim()?.ifEmpty { null }
}
