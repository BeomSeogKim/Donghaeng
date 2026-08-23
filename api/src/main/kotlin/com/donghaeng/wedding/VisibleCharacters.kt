package com.donghaeng.wedding

/**
 * **보이지 않는 문자로만 된 이름은 이름으로 치지 않는다** — the founder's rule
 * (notes/2026-08-22-decision-the-seat-name-edit.md §5), as one predicate.
 *
 * It lives on its own since 2026-08-23, when [WeddingName] arrived and there were
 * about to be two copies of it — the same thing §4 of that record gathered
 * `@SeatName` for. **The rule is one implementation and several annotations**: an
 * annotation names *which field* is bound and what width it lands in, this names
 * what a name is. `#191` (하객 이름) reads this rather than deriving its own.
 *
 * A name must contain at least one **visible** character: a code point that is
 * neither whitespace nor in Unicode general category C (`Cc` control, `Cf` format,
 * `Cs` surrogate, `Co` private use).
 *
 * **It walks CODE POINTS, not `Char`s, and that is load-bearing rather than
 * pedantic.** A supplementary-plane character is a surrogate PAIR, so a per-`Char`
 * version reads two `Cs` and calls the whole name invisible: 🙂 and CJK Ext B hanja
 * — which do appear in real names — were both refused by the first draft. Measured,
 * not reasoned about. `Character.isSpaceChar` sits beside `isWhitespace` for the
 * mirror-image reason: the `Character` predicate excludes U+00A0 where Kotlin's
 * `Char.isWhitespace()` includes it, so without it a name of one NBSP passes.
 *
 * **`Co` (private use) is refused deliberately.** Nothing guarantees it renders, and
 * it is not a character a couple types; a name we cannot draw is not a name they can
 * read on their own ledger.
 *
 * **`Cn` (unassigned) is NOT refused, and that is the same question answered the
 * other way.** `Cn` does not mean "assigned to nothing" — it means "not in THIS
 * JVM's Unicode tables", which is a fact about our runtime and not about the
 * character. **JDK 21 carries Unicode 15.0, and CJK Extension I (U+2EBF0–U+2EE5D)
 * arrived in 15.1**: unified ideographs, so hanja, so name-bearing by definition.
 * Including `Cn` would refuse a legitimate 이름 whose only fault is being newer than
 * our JDK, and it would get worse the longer a runtime stays put — the failure gets
 * *quieter* over time, not louder. The cost of leaving it out is that a name of one
 * never-assigned code point (U+0378) is accepted; that is not something a couple
 * types, and it is a far smaller wrong than refusing a real surname.
 */
internal object VisibleCharacters {
    fun presentIn(value: String): Boolean = value.codePoints().anyMatch(::isVisible)

    private fun isVisible(codePoint: Int): Boolean =
        !Character.isWhitespace(codePoint) &&
            !Character.isSpaceChar(codePoint) &&
            Character.getType(codePoint) !in INVISIBLE_CATEGORIES

    /**
     * Category C **without `Cn`** — see the KDoc above. `Cn` is the one member of the
     * category that means "this JDK has not heard of it" rather than "this draws
     * nothing", and on JDK 21 that includes CJK Extension I.
     */
    private val INVISIBLE_CATEGORIES: Set<Int> =
        setOf(
            CharCategory.CONTROL,
            CharCategory.FORMAT,
            CharCategory.SURROGATE,
            CharCategory.PRIVATE_USE,
        ).map { it.value.toInt() }.toSet()
}
