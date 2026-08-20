package com.donghaeng.guest

import com.donghaeng.wedding.WeddingSide

/**
 * A ledger row as the API publishes it — public, because `web/` generates a
 * TypeScript type from this shape, and resource-named so `#12`'s edit and `#15`'s
 * list return the same one.
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
    val expectedPartySize: Int,
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
        expectedPartySize = expectedPartySize,
    )
