package com.donghaeng.auth.account

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence

/**
 * **Which columns of `app_user` a login may rewrite.** `#94` added the first `UPDATE`
 * against that table outside account creation, and the row it updates is the one the
 * account merge key lives on.
 *
 * The rule is one sentence — *an update to `app_user` sets `name` and `updated_at`
 * and nothing else* — and it was prose in a KDoc until this file existed. A rule a
 * test can hold does not also live in prose
 * (`notes/2026-08-12-decision-auth-package-structure.md` is the record of that
 * lesson costing this repo twice).
 *
 * **Why the columns and not "exactly one `@Modifying` query".** Counting queries
 * says a second write is suspicious; it does not say what is wrong with the first
 * one, and a legitimate second write — `#92`'s activity stamp was already proposed
 * and declined — would have to delete the check to land. This form states the actual
 * invariant, binds every statement anywhere in `src/main` rather than one interface,
 * and fails on precisely the shape `#110` will be tempted by:
 * `set email = coalesce(email, :mergeKey)`, which is a provider-supplied address
 * becoming a merge key with nobody having verified it — the takeover the 2026-08-13
 * record's `#94` §2 exists to prevent, reached through the back door.
 *
 * Source-tree scanning rather than bytecode, for the same reason `SourceShapeTest`
 * does it: a string literal is not in the class file in a form worth matching.
 */
internal class AppUserWriteScopeTest {
    private val sourceRoot = Path.of("src/main/kotlin")

    /** `update app_user … set … where` — the set-clause is what this test reads. */
    private val scopedUpdate =
        Regex("""update\s+app_user\b(.*?)\bwhere\b""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    private val anyUpdate = Regex("""update\s+app_user\b""", RegexOption.IGNORE_CASE)

    private fun sources(): List<Path> =
        Files
            .walk(sourceRoot)
            .asSequence()
            .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
            .sorted()
            .toList()

    @Test
    fun `an update to app_user writes the display name and the stamp, and no other column`() {
        val written =
            sources().flatMap { path ->
                val source = Files.readString(path)

                // An UPDATE with no WHERE clause escapes the scan above entirely, so
                // it is counted rather than assumed away — and it would be its own
                // alarm: this table is written one person at a time.
                assertThat(scopedUpdate.findAll(source).count())
                    .describedAs("%s holds an `update app_user` with no where clause", path)
                    .isEqualTo(anyUpdate.findAll(source).count())

                scopedUpdate.findAll(source).flatMap { match ->
                    match.groupValues[1]
                        .substringAfter("set")
                        .split(",")
                        .map { it.substringBefore("=").trim() }
                }
            }

        assertThat(written).isNotEmpty()
        assertThat(written).containsOnly("name", "updated_at")
    }

    @Test
    fun `the scan finds the statement it exists for, so it cannot pass vacuously`() {
        // Every assertion above is "for each match", which no matches satisfies. The
        // named statement is `#94`'s, and if it is ever renamed this fails loudly
        // rather than quietly stopping.
        val statements = sources().sumOf { anyUpdate.findAll(Files.readString(it)).count() }

        assertThat(statements).isGreaterThanOrEqualTo(1)
    }
}
