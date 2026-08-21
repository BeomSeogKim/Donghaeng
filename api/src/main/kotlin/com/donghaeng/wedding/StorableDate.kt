package com.donghaeng.wedding

import com.donghaeng.json.Patch
import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import java.time.LocalDate
import kotlin.reflect.KClass

/**
 * A date the column can actually hold.
 *
 * `LocalDate` spans ±999999999 years and Jackson's ISO parser accepts an expanded
 * year, while PostgreSQL `date` stops at 5874897 AD — so `+5874898-01-01`
 * deserialises cleanly, reaches the column, and comes back as a masked 500. That is
 * the same rule the names' `@Size` follows: **a cast is not a validator**
 * (notes/2026-08-17-decision-log-masking-mechanism.md).
 *
 * **The bound is the column's, not the product's.** Whether a wedding may be ten
 * years out is a domain question nobody has decided, and inventing one here would
 * refuse real couples; this only refuses what cannot be stored. It is an annotation
 * rather than a check inside one request DTO so that `#8`, which PATCHes the same
 * column, gets the same refusal without repeating the reasoning.
 *
 * **Two validators, one bound** (2026-08-22, `#173`): `#8` arrived and its date
 * travels inside a [Patch], so a second [ConstraintValidator] reads it there.
 * Hibernate Validator picks between them by the annotated element's declared type.
 * A second annotation would have meant a second copy of the range, and one copy of
 * a range is eventually the one nobody updated.
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [StorableDateValidator::class, StorablePatchedDateValidator::class])
annotation class StorableDate(
    val message: String = "must be a date the database can store",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

internal class StorableDateValidator : ConstraintValidator<StorableDate, LocalDate> {
    override fun isValid(
        value: LocalDate?,
        context: ConstraintValidatorContext,
    ): Boolean = value == null || value in STORABLE
}

/**
 * The same bound, read out of a [Patch] — a member nobody sent and a member sent as
 * `null` are both accepted here, since neither is a date this column would have to
 * store. Refusing the cleared one is [com.donghaeng.json.NotCleared]'s job and is a
 * different question.
 */
internal class StorablePatchedDateValidator : ConstraintValidator<StorableDate, Patch<*>> {
    override fun isValid(
        value: Patch<*>?,
        context: ConstraintValidatorContext,
    ): Boolean {
        val date = (value as? Patch.Set)?.value ?: return true
        return date is LocalDate && date in STORABLE
    }
}

/**
 * PostgreSQL `date` runs 4713 BC – 5874897 AD. The lower bound is stated in
 * Julian-calendar terms, so it is taken conservatively as ISO year -4712: being a
 * few days strict at the bottom of an era nobody marries in costs nothing, and being
 * one day generous at the top is a 500.
 *
 * File-private rather than a companion, so that both validators read one range.
 */
private val STORABLE: ClosedRange<LocalDate> = LocalDate.of(-4712, 1, 1)..LocalDate.of(5874897, 12, 31)
