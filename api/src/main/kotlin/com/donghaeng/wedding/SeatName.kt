package com.donghaeng.wedding

import jakarta.validation.Constraint
import jakarta.validation.Payload
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import kotlin.reflect.KClass

/** The `varchar(100)` a seat's name lands in. Private, so no sibling package can inline it out of sight of the architecture rules. */
private const val MAX_LENGTH = 100

/**
 * A person's own name as `wedding_party.name` can hold it: 1–100 characters, not
 * whitespace only, measured **as sent** and before the write point trims it.
 *
 * **One rule, one place.** Three requests carry a seat name — 웨딩 만들기, 초대 수락 and
 * `#187`'s edit — and the same two constraints had been written out twice before the
 * third arrived. A copy per request is how the three drift, and a name refused on the
 * screen that creates it but accepted on the screen that fixes it is a bug nothing
 * else would catch.
 *
 * `@Size` is the column's width; `@NotBlank` is what refuses a name the trim would
 * empty. Composed rather than hand-validated so that both keep their own messages —
 * somebody who sent 101 characters and somebody who sent spaces are told different
 * things, exactly as they were before this existed.
 *
 * **It does not check for null**, and could not usefully: every request carrying a
 * name declares it non-null, so an omitted member fails while the body is read and
 * never reaches a validator.
 *
 * **springdoc does not walk a composed constraint**, so each member that wears this
 * declares its own `maxLength` to `@Schema` — the bound is on the seam and `web/`
 * generates from that document.
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [])
@NotBlank
@Size(max = MAX_LENGTH)
annotation class SeatName(
    val message: String = "must be a name of 1 to $MAX_LENGTH characters",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)
