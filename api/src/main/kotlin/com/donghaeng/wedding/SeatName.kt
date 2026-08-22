package com.donghaeng.wedding

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import jakarta.validation.constraints.Size
import kotlin.reflect.KClass

/** The `varchar(100)` a seat's name lands in. Private, so no sibling package can inline it out of sight of the architecture rules. */
private const val MAX_LENGTH = 100

/**
 * A person's own name as `wedding_party.name` can hold it: **not blank once trimmed**,
 * and at most 100 characters as sent.
 *
 * **One rule, one place.** Three requests carry a seat name — 웨딩 만들기, 초대 수락 and
 * `#187`'s edit — and the same constraints had been written out twice before the third
 * arrived. A copy per request is how the three drift, and a name refused on the screen
 * that creates it but accepted on the screen that fixes it is a bug nothing else would
 * catch.
 *
 * **Blankness is decided on the trimmed value, with the same `trim()` the write points
 * call, and that is the whole reason this carries a validator of its own.** `@NotBlank`
 * looks blank-ish but is a different function: Hibernate Validator's `NotBlankValidator`
 * uses **Java**'s `String.trim()`, which strips only characters ≤ U+0020, while Kotlin's
 * `trim()` also strips U+3000, U+00A0 and U+2000–U+200A. **U+3000 (전각 공백) is an
 * ordinary key on a Korean IME**, so `{"name":"　"}` passed `@NotBlank`, was emptied by
 * the service's trim, and was stored as `''` — the column has no CHECK to catch it.
 * Measuring what will actually be stored is what closes that gap, and it closes it for
 * every request wearing this rather than at three write points.
 *
 * **[Size] stays a composed constraint and measures the value AS SENT**, which is a
 * bound the trim can only make slacker: trimming shortens, so a value that passes here
 * still fits the column. It is also what publishes `maxLength` to springdoc, which does
 * walk a composition — verified by deleting a hand-written `@Schema(maxLength)` and
 * regenerating the document unchanged.
 *
 * Two constraints and so two messages: somebody who sent 101 characters and somebody who
 * sent spaces are still told different things.
 *
 * It passes `null` on to the body reader: every request carrying a name declares it
 * non-null, so an omitted member fails while the body is read and never reaches here.
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [SeatNameValidator::class])
@Size(max = MAX_LENGTH)
annotation class SeatName(
    val message: String = "must not be blank once trimmed",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

internal class SeatNameValidator : ConstraintValidator<SeatName, String> {
    override fun isValid(
        value: String?,
        context: ConstraintValidatorContext,
    ): Boolean = value == null || value.trim().isNotEmpty()
}
