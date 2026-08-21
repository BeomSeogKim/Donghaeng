package com.donghaeng.json

import com.fasterxml.jackson.databind.annotation.JsonDeserialize

/**
 * What a member of a partial-update body can be: **absent, `null`, or a value** —
 * three states, because on a PATCH they mean three different things
 * (notes/2026-08-22-decision-partial-update-shape.md).
 *
 * A plain nullable property cannot carry this. Jackson leaves it `null` both when
 * the member was omitted and when it was sent as `null`, so an endpoint reading one
 * has to guess between "leave it alone" and "clear it" — and on a create, where both
 * take the default, the guess is free and `CreateGuestRequest` takes it. On an
 * update it is not free: guessing "leave it alone" makes an unset state
 * unreachable, and guessing "clear it" wipes every field the caller did not send.
 *
 * The three states are a sealed hierarchy rather than a nullable payload so that
 * every reader is an exhaustive `when` the compiler checks, and so that "the caller
 * sent null" is a case with a name rather than a null inside a wrapper:
 *
 * ```
 * when (val side = request.side) {
 *     Patch.Absent -> {}                 // the caller did not mention it
 *     Patch.Cleared -> row.side = null   // the caller sent null
 *     is Patch.Set -> row.side = side.value
 * }
 * ```
 *
 * **[Cleared] is not automatically legal.** A member backed by a NOT NULL column has
 * no cleared state, and `@NotCleared` is what refuses one — as a 400 like any other
 * field error, rather than as the masked 500 the column would produce.
 */
@JsonDeserialize(using = PatchDeserializer::class)
sealed interface Patch<out T> {
    /** The member was not in the body at all: leave what is stored alone. */
    data object Absent : Patch<Nothing>

    /** The member was sent as `null`: store no value, where that is a state at all. */
    data object Cleared : Patch<Nothing>

    /** The member was sent with a value. */
    data class Set<out T>(
        val value: T,
    ) : Patch<T>
}
