package com.donghaeng.wedding

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
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [StorableDateValidator::class])
annotation class StorableDate(
    val message: String = "must be a date the database can store",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

internal class StorableDateValidator : ConstraintValidator<StorableDate, LocalDate> {
    override fun isValid(
        value: LocalDate?,
        context: ConstraintValidatorContext,
    ): Boolean = value == null || value in EARLIEST..LATEST

    private companion object {
        /**
         * PostgreSQL `date` runs 4713 BC – 5874897 AD. The lower bound is stated in
         * Julian-calendar terms, so it is taken conservatively as ISO year -4712:
         * being a few days strict at the bottom of an era nobody marries in costs
         * nothing, and being one day generous at the top is a 500.
         */
        private val EARLIEST: LocalDate = LocalDate.of(-4712, 1, 1)
        private val LATEST: LocalDate = LocalDate.of(5874897, 12, 31)
    }
}
