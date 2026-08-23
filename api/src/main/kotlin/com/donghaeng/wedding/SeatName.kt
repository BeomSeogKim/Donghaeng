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
 * A person's own name as `wedding_party.name` can hold it: **it must contain a visible
 * character**, and it may be at most 100 characters as sent.
 *
 * **This is the product's one name rule and `#191` copies it rather than re-deriving
 * one** — 하객 이름 has the same defect and the same fix.
 *
 * **One rule, one place.** Three requests carry a seat name — 웨딩 만들기, 초대 수락 and
 * `#187`'s edit — and the same constraints had been written out twice before the third
 * arrived. A copy per request is how the three drift, and a name refused on the screen
 * that creates it but accepted on the screen that fixes it is a bug nothing else would
 * catch.
 *
 * **보이지 않는 문자로만 된 이름은 이름으로 치지 않는다** — the founder's rule
 * (notes/2026-08-22-decision-the-seat-name-edit.md §5), and the reason this carries a
 * validator of its own rather than `@NotBlank`. A name must contain **at least one
 * visible character**: something that is neither whitespace nor in Unicode general
 * category C (`Cc` control, `Cf` format, `Cs` surrogate, `Co` private use, `Cn`
 * unassigned).
 *
 * **It replaces a trim comparison, and that is a change of question rather than a wider
 * net.** The bug that started this was that `@NotBlank` and the services' `trim()` are
 * different functions whose sets merely OVERLAP — Java's trims `c <= ' '`, Kotlin's
 * trims `isWhitespace() || isSpaceChar()` — so each admitted names the other refused:
 * `"　"` (U+3000, an ordinary key on a Korean IME) validated and stored as `''`, while a
 * NUL survived a Kotlin-only predicate and reached PgJDBC, which refuses it in a text
 * parameter as a masked 500. Asking "is there a visible character in it" answers both
 * for a reason instead of by union, and it also answers what neither trim ever could:
 * `"\u200b"`, `"\ufeff"` and `"\u00ad"` are stripped by no trim in either language and
 * would have landed as a seat labelled with nothing.
 *
 * **The predicate itself lives in [VisibleCharacters]**, which is where its two
 * measured implementation facts are written down — it walks code points rather than
 * `Char`s, and it does not refuse `Cn`. It moved there on 2026-08-23 when
 * [WeddingName] arrived needing the same rule: an annotation says which field is
 * bound and what width it lands in, the predicate says what a name is, and one rule
 * with two copies is what §4 of the record above exists to prevent.
 *
 * **[Size] stays a composed constraint and measures the value AS SENT**, which is a
 * bound the write point's `trim()` can only make slacker: trimming shortens, so a value
 * that passes here still fits the column. It is also what publishes `maxLength` to
 * springdoc, which does walk a composition — held by `OpenApiDocumentTest`, since
 * `openapi-typescript` drops `maxLength` and so the `seam` job can never notice its
 * loss.
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
    val message: String = "must contain at least one visible character",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

internal class SeatNameValidator : ConstraintValidator<SeatName, String> {
    override fun isValid(
        value: String?,
        context: ConstraintValidatorContext,
    ): Boolean = value == null || VisibleCharacters.presentIn(value)
}
