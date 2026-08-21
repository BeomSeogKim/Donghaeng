package com.donghaeng.guest

import com.donghaeng.wedding.WeddingScope
import com.donghaeng.wedding.WeddingService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The wedding's 인원수, computed here and nowhere else — **the API returns the
 * conclusion, never the rows to add up** (api/AGENTS.md, the standing client rule).
 *
 * Its own service rather than a third method on [GuestService]: this reads two
 * domains to answer one question, and [GuestService] writes ledger rows. It is
 * called from both sides of that split — by [HeadcountController] for the screen's
 * own read, and by [GuestService] inside the write transaction, so that a mutation
 * answers with the number it just moved rather than with the number before it.
 */
@Service
internal class HeadcountService(
    private val guests: GuestRepository,
    private val weddings: WeddingService,
) {
    /**
     * **Takes the resolved [WeddingScope], never a bare id.** The number is an
     * aggregate over one wedding's whole ledger, so the id it is computed for has to
     * be one `user → membership → wedding` has already accepted; a `Long` parameter
     * here would make "sum a wedding the caller has no claim on" a compiling
     * mistake.
     *
     * `sum` and 보증인원 are two reads and are deliberately not one join: the count
     * belongs to `guest/` and the venue's number to `wedding/`, and this package may
     * not reach into `wedding/`'s rows
     * (notes/2026-08-21-decision-the-headcount-endpoint.md §3). Called inside
     * [GuestService.create]'s transaction it joins it, so the row just written is in
     * the sum — `IDENTITY` ids mean the insert has already been issued by then.
     */
    @Transactional(readOnly = true)
    fun of(wedding: WeddingScope): HeadcountResponse =
        HeadcountResponse(
            // A Long from the database because `sum` widens; a headcount is an Int on
            // the wire, and this narrowing is safe by four orders of magnitude — a
            // real ledger is 200–800 rows
            // (notes/2026-08-20-decision-the-ledger-read-and-its-filters.md §1).
            mealHeadcount = guests.sumAttendingPartySize(wedding.id).toInt(),
            guaranteedHeadcount = weddings.guaranteedHeadcountOf(wedding.id),
        )
}
