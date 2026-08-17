package com.donghaeng.wedding

import java.time.LocalDate

/**
 * A wedding as the API publishes it — public, because `web/` generates a TypeScript
 * type from this shape.
 *
 * **No `guaranteedHeadcount`**, for the reason `MeResponse` has no `email`: nothing
 * sets it here and no screen reads it from this response, and a published field
 * nothing consumes is a seam commitment with no requirement behind it. `#8` adds it
 * with the endpoint that can set it. **No `createdBy`** either — who created a
 * wedding is an audit fact, not something a screen renders.
 */
data class WeddingResponse(
    val id: Long,
    val weddingDate: LocalDate,
    val groomName: String,
    val brideName: String,
)

internal fun Wedding.toWeddingResponse() =
    WeddingResponse(
        id = id,
        weddingDate = weddingDate,
        groomName = groomName,
        brideName = brideName,
    )
