package com.donghaeng.guest

import com.donghaeng.wedding.UpdateWeddingRequest
import com.donghaeng.wedding.WeddingScope
import com.donghaeng.wedding.WeddingService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The write to the wedding and the recomputed 인원수, **in one transaction** — which
 * is the only thing this class does and the entire reason it exists.
 *
 * ## Why a wedding endpoint is served from `guest/`
 *
 * Because the arrow between these two packages is spent, and it points this way
 * (notes/2026-08-21-decision-the-headcount-endpoint.md §3): `guest/` already depends
 * on `wedding/` for [WeddingScope] and 측, so a class in `wedding/` naming
 * [HeadcountService] would close a package cycle `ArchitectureTest` refuses. Every
 * mutation response carries the recomputed aggregate (root `AGENTS.md`), and the
 * aggregate is a fold over the ledger — so **any** endpoint that mutates a
 * wedding-scoped resource and answers with the number is assembled on the ledger's
 * side of that arrow, whatever resource it writes. `#175`'s seat-name edit lands
 * here for the same reason.
 *
 * What stays in `wedding/` is the write itself, [WeddingService.update] — the row,
 * its invariants and its clock belong to the package that owns the row.
 *
 * ## Why one transaction and not two calls from the controller
 *
 * The number has to be the number *after* the write, and read in the write's own
 * transaction it is: the persistence context is the same, so
 * `WeddingService.guaranteedHeadcountOf` reads the value this request just assigned
 * rather than the row as it was committed. Two service calls from a controller would
 * be two transactions and a window between them — the same reasoning
 * [GuestService.create] follows for the row it has just inserted.
 */
@Service
internal class WeddingUpdateService(
    private val weddings: WeddingService,
    private val headcounts: HeadcountService,
) {
    @Transactional
    fun update(
        wedding: WeddingScope,
        request: UpdateWeddingRequest,
    ): WeddingMutationResponse = WeddingMutationResponse(weddings.update(wedding, request), headcounts.of(wedding))
}
