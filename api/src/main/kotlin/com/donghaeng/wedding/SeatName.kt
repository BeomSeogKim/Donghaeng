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
 * **It walks CODE POINTS, not `Char`s, and that is load-bearing rather than pedantic.**
 * A supplementary-plane character is a surrogate PAIR, so a per-`Char` version reads two
 * `Cs` and calls the whole name invisible: 🙂 and CJK Ext B hanja — which do appear in
 * real names — were both refused by the first draft. Measured, not reasoned about.
 * `Character.isSpaceChar` is here beside `isWhitespace` for the mirror-image reason: the
 * `Character` predicate excludes U+00A0 where Kotlin's `Char.isWhitespace()` includes
 * it, so without it a name of one NBSP passes.
 *
 * **`Co` (private use) is refused deliberately.** Nothing guarantees it renders, and it
 * is not a character a couple types; a name we cannot draw is not a name they can read
 * on their own ledger.
 *
 * **`Cn` (unassigned) is NOT refused, and that is the same question answered the other
 * way.** `Cn` does not mean "assigned to nothing" — it means "not in THIS JVM's Unicode
 * tables", which is a fact about our runtime and not about the character. **JDK 21
 * carries Unicode 15.0, and CJK Extension I (U+2EBF0–U+2EE5D) arrived in 15.1**:
 * unified ideographs, so hanja, so name-bearing by definition. Measured on this build —
 * every Extension I code point reads `Cn` here while rendering on the phone that typed
 * it, and Extensions B through H read as letters. Including `Cn` would refuse a
 * legitimate 이름 whose only fault is being newer than our JDK, and it would get worse
 * the longer a runtime stays put — the failure gets *quieter* over time, not louder.
 * The cost of leaving it out is that a name of one never-assigned code point (U+0378)
 * is accepted; that is not something a couple types, and it is a far smaller wrong than
 * refusing a real surname.
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
    ): Boolean = value == null || value.codePoints().anyMatch(::isVisible)

    private fun isVisible(codePoint: Int): Boolean =
        !Character.isWhitespace(codePoint) &&
            !Character.isSpaceChar(codePoint) &&
            Character.getType(codePoint) !in INVISIBLE_CATEGORIES

    private companion object {
        /**
         * Category C **without `Cn`** — see the KDoc above. `Cn` is the one member of the
         * category that means "this JDK has not heard of it" rather than "this draws
         * nothing", and on JDK 21 that includes CJK Extension I.
         */
        val INVISIBLE_CATEGORIES: Set<Int> =
            setOf(
                CharCategory.CONTROL,
                CharCategory.FORMAT,
                CharCategory.SURROGATE,
                CharCategory.PRIVATE_USE,
            ).map { it.value.toInt() }.toSet()
    }
}
