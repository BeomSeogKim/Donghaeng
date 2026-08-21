package com.donghaeng.json

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

/**
 * A [Patch] member that has no cleared state: sending it as `null` is a 400.
 *
 * **Every member backed by a NOT NULL column wears this**, and the reason it is a
 * constraint rather than a check in a service is the rule `StorableDate` states —
 * a cast is not a validator, and what is not refused at the edge is refused by the
 * column, which reaches the caller as a masked 500
 * (notes/2026-08-17-decision-log-masking-mechanism.md).
 *
 * It is separate from the constraint that bounds the value because the two answer
 * different questions: `@StorableDate` asks whether a date can be stored, this asks
 * whether "no date" is a thing this column can mean. A member wears both.
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [NotClearedValidator::class])
annotation class NotCleared(
    val message: String = "cannot be cleared",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

internal class NotClearedValidator : ConstraintValidator<NotCleared, Patch<*>> {
    override fun isValid(
        value: Patch<*>?,
        context: ConstraintValidatorContext,
    ): Boolean = value !== Patch.Cleared
}
