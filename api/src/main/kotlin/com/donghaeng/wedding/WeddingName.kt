package com.donghaeng.wedding

import com.donghaeng.json.Patch
import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

/** The `varchar(100)` 결혼식 이름 lands in. Private, so no sibling package can inline it out of sight. */
private const val MAX_LENGTH = 100

/**
 * 결혼식 이름 as `wedding.name` can hold it: **it must contain a visible character**,
 * and it may be at most 100 characters as sent
 * (notes/2026-08-23-decision-the-wedding-has-a-name.md).
 *
 * **The name rule is [VisibleCharacters] and this does not restate it** — 보이지 않는
 * 문자로만 된 이름은 이름으로 치지 않는다 binds every name field in the product, and a
 * name the couple cannot see is a wedding whose header renders nothing. What this
 * annotation adds is which field is bound and what width it lands in.
 *
 * **A separate annotation from [SeatName], and that is forced rather than chosen.**
 * `#8`'s partial update carries this member inside a [Patch], and the composed
 * `@Size` [SeatName] relies on cannot read one — Hibernate Validator resolves a
 * constraint by the annotated element's declared type, so a composed `@Size` on a
 * `Patch<String>` is an `UnexpectedTypeException` at the first request. The bound is
 * therefore checked by both validators below, in one file, from one constant. This is
 * the "budget a `Patch`-typed validator as a standing cost of the wrapper" that
 * notes/2026-08-22-decision-partial-update-shape.md §1 named.
 *
 * **The consequence on the seam, stated because it differs from the seat's name:**
 * springdoc publishes no `maxLength` for this member, since nothing composed says so.
 * That costs the generated types nothing — `openapi-typescript` drops `maxLength`
 * either way (notes/2026-08-22-decision-the-seat-name-edit.md §4) — and the bound is
 * published where meaning is published, `docs/api-spec.md`.
 *
 * Both validators pass `null`: an omitted member on the create request and a cleared
 * one on the patch are both "the couple has no name for it", which is the ordinary
 * state of a wedding and not something to refuse here.
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [WeddingNameValidator::class, PatchedWeddingNameValidator::class])
annotation class WeddingName(
    val message: String = "must be at most 100 characters and contain at least one visible character",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

/** 웨딩 만들기's member, where the name arrives as a plain nullable string. */
internal class WeddingNameValidator : ConstraintValidator<WeddingName, String> {
    override fun isValid(
        value: String?,
        context: ConstraintValidatorContext,
    ): Boolean = value == null || storable(value)
}

/**
 * The same bound read out of a [Patch] — absent and cleared both pass, since neither
 * is a name this column would have to store. Whether it may be cleared at all is
 * [com.donghaeng.json.NotCleared]'s question, and the answer for this member is yes:
 * a wedding with no name is an ordinary wedding.
 */
internal class PatchedWeddingNameValidator : ConstraintValidator<WeddingName, Patch<*>> {
    override fun isValid(
        value: Patch<*>?,
        context: ConstraintValidatorContext,
    ): Boolean {
        val name = (value as? Patch.Set)?.value ?: return true
        return name is String && storable(name)
    }
}

/**
 * Measured **as sent**, before the write point's `trim()`, which is a bound the trim
 * can only make slacker: trimming shortens, so a value that passes here still fits
 * the column. The count is in UTF-16 code units, exactly as `@Size` counts.
 */
private fun storable(value: String): Boolean = value.length <= MAX_LENGTH && VisibleCharacters.presentIn(value)
