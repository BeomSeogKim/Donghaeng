package com.donghaeng.guest

import com.donghaeng.wedding.WeddingSide

/**
 * A ledger row as the API publishes it — public, because `web/` generates a
 * TypeScript type from this shape, and resource-named so `#12`'s edit and `#15`'s
 * list return the same one.
 *
 * **`expectedPartySize` is gone and [companionOf] replaced it** (changed 2026-08-23,
 * `#213`). A party of three is three of these, and the third of them says whose it
 * is: `null` on the head, the head's `id` on everyone they brought
 * (notes/2026-08-23-decision-companions-become-guests.md). That is what lets a
 * companion surfaced on its own — by a search, by a filter — still say whose it is,
 * and it is the second half of the same fact the generated name carries.
 *
 * **No `confirmed*`** — nothing in v1 writes them, and `docs/api-spec.md` tells the
 * client so; they arrive with the endpoint that can set them (`#12`, `#23`). **No
 * `weddingId`**, which the caller sent in the path, and no `createdBy` /
 * `updatedBy`, which are audit facts `GuestChange` answers. Adding a member later
 * breaks no client.
 */
data class GuestResponse(
    val id: Long,
    val name: String,
    val side: WeddingSide,
    val groupCategory: GuestGroupCategory,
    val groupLabel: String?,
    val contact: String?,
    val accessibilityNote: String?,
    val expectedAttending: Boolean,
    val companionOf: Long?,
)

internal fun Guest.toGuestResponse() =
    GuestResponse(
        id = id,
        name = name,
        side = side,
        groupCategory = groupCategory,
        groupLabel = groupLabel,
        contact = contact,
        accessibilityNote = accessibilityNote,
        expectedAttending = expectedAttending,
        companionOf = companionOf,
    )
