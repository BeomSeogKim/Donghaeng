package com.donghaeng.wedding

import com.donghaeng.json.Patch
import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

/**
 * 보증인원 the column can hold — `ck_wedding_guaranteed_headcount check
 * (guaranteed_headcount > 0)`, which `V1__baseline_schema.sql` argues is an
 * invariant and not policy: there is no reading of a contracted guarantee under
 * which zero or a negative means anything, and "not agreed yet" is already spelled
 * NULL.
 *
 * **It is the column's bound and not a product one**, exactly as `StorableDate` is:
 * we do not know what a plausible 보증인원 is, because it is the venue's number and
 * never ours. What this refuses is only what cannot be stored — unvalidated that is
 * a CHECK violation, which reaches the caller as a masked 500 rather than as the 400
 * it is (notes/2026-08-17-decision-log-masking-mechanism.md).
 *
 * Cleared and absent both pass: whether 보증인원 may be cleared is
 * [com.donghaeng.json.NotCleared]'s question, and the answer here is yes.
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [StorableHeadcountValidator::class])
annotation class StorableHeadcount(
    val message: String = "must be a headcount the database can store",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

internal class StorableHeadcountValidator : ConstraintValidator<StorableHeadcount, Patch<*>> {
    override fun isValid(
        value: Patch<*>?,
        context: ConstraintValidatorContext,
    ): Boolean {
        val headcount = (value as? Patch.Set)?.value ?: return true
        return headcount is Int && headcount > 0
    }
}
