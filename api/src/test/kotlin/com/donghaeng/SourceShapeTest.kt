package com.donghaeng

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence

/**
 * File shape, checked rather than described — and **honest about where the check
 * stops**, which the first version was not.
 *
 * It reads the SOURCE tree, which [ArchitectureTest] deliberately does not: file
 * names, declaration counts and `const` all vanish into bytecode. That is also why
 * this is a hand-written scan rather than another architecture-testing dependency
 * for three assertions.
 *
 * ## What this does NOT cover, stated because deleting the prose once already went
 * wrong
 *
 * `api/AGENTS.md` said "never a layer bucket", the prose was deleted on the
 * grounds that this file replaced it, and it did not: the check below fires only
 * when a persistence or configuration type shares a file. **`AuthServices.kt`
 * holding two services, or any file holding two ordinary unrelated types, still
 * ships unchallenged** — Kotlin's convention permits several declarations per file
 * when they are *closely related*, and relatedness is a judgement no regex has.
 *
 * So the rule is back in `api/AGENTS.md`, narrowed to what a person must still
 * judge. That is the second time in this stop that "it is mechanically checked, so
 * the prose goes" turned out hollow; the lesson is in
 * `notes/2026-08-12-decision-auth-package-structure.md`.
 */
class SourceShapeTest {
    private val sourceRoot = Path.of("src/main/kotlin")

    /** Top-level declarations only — column zero, so nested types do not count. */
    private val declaration =
        Regex(
            """^(?:(?:internal|private|public|abstract|sealed|data|annotation|open|enum|value)\s+)*""" +
                """(?:class|interface|object)\s+(\w+)""",
            RegexOption.MULTILINE,
        )

    private fun sources(): List<Path> =
        Files
            .walk(sourceRoot)
            .asSequence()
            .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
            .sorted()
            .toList()

    private fun declarationsIn(path: Path): List<String> = declaration.findAll(Files.readString(path)).map { it.groupValues[1] }.toList()

    @Test
    fun `the scan actually finds the sources`() {
        // Every assertion below is "for each file", which an empty list satisfies.
        assertThat(sources().map { it.fileName.toString() })
            .contains("AppUser.kt", "AppUserRepository.kt", "SessionService.kt", "SessionProperties.kt")
    }

    @Test
    fun `a persistence or configuration type is the only declaration in its file`() {
        // The layer-bucket prohibition at the scale a regex can reach.
        // `AuthRepositories.kt` held three; an entity file holding its own
        // repository was the same mistake one step quieter; and a properties class
        // had been placed three different ways in one package before this rule
        // existed. Each of these is the boundary of a layer or of configuration,
        // and each gets its own file — the shape JetBrains publishes for Spring.
        sources().forEach { path ->
            val declared = declarationsIn(path)
            val source = Files.readString(path)
            val marker =
                when {
                    declared.any { it.endsWith("Repository") } -> "a repository"
                    "\n@Entity" in source -> "an @Entity"
                    "\n@ConfigurationProperties" in source -> "a @ConfigurationProperties class"
                    else -> return@forEach
                }

            assertThat(declared)
                .describedAs("%s declares %s alongside %s", path, marker, declared)
                .hasSize(1)
        }
    }

    @Test
    fun `a file declaring one type is named after it`() {
        // Kotlin's convention allows several declarations per file as long as they
        // are closely related AND the file name says what it contains. The second
        // half is what a two-type file named after one of them was quietly
        // failing. Only the single-declaration case is checkable, so only it is
        // checked; a multi-declaration file's name stays a judgement — see the
        // class comment.
        sources().forEach { path ->
            val declared = declarationsIn(path)
            if (declared.size != 1) return@forEach

            assertThat(path.fileName.toString())
                .describedAs("%s declares only %s", path, declared.single())
                .isEqualTo("${declared.single()}.kt")
        }
    }

    @Test
    fun `a redirect is only ever sent from the OAuth handlers, and never from the request`() {
        // The open-redirect rule, held here rather than written into
        // `api/AGENTS.md`: a rule a test can hold does not also live in prose. It
        // is the same shape as #80's `wedding_id` allowlist — an ALLOWLIST of the
        // files permitted to redirect at all, so a new one has to be added
        // deliberately.
        //
        // Two halves, and the second is the one that matters. WHERE, because the
        // browser reaches the callback holding a session issued one line earlier
        // and every other endpoint in this API answers JSON. WHAT, because the
        // destination is configuration — never a `redirect_uri` parameter, never a
        // `Referer`, never a URL smuggled through `state`. Any mention of
        // `request` inside the argument fails, which is coarse on purpose: a
        // redirect target assembled from the request is not a thing to review case
        // by case.
        val redirect = Regex("""\.sendRedirect\(([^\n]*)""")
        sources().forEach { path ->
            redirect.findAll(Files.readString(path)).forEach { match ->
                assertThat(path.fileName.toString())
                    .describedAs("%s redirects; only the OAuth handlers may", path)
                    .isIn(MAY_REDIRECT)
                assertThat(match.groupValues[1])
                    .describedAs("%s builds a redirect target out of the request", path)
                    .doesNotContain("request")
            }
        }
    }

    @Test
    fun `no constant is visible outside its own file`() {
        // A `const val` is INLINED at every call site, so nothing referencing the
        // declaring class survives into the caller's class file — and
        // [ArchitectureTest] reads class files. A sibling package reaching for
        // `SecurityConfig.CALLBACK_PATH` was therefore a real dependency that
        // every bytecode rule was blind to; it was verified as such before this
        // rule existed. `val` in the same place compiles to a field read and is
        // seen.
        //
        // Private constants are exempt because nothing outside the file can name
        // them, so no cross-package dependency can hide in one. The same blind spot
        // survives for `inline` functions, which cannot be forbidden this way and
        // are recorded rather than checked.
        sources().forEach { path ->
            val lines = Files.readString(path).lines()
            lines.forEachIndexed { index, line ->
                if ("const val" !in line || "private const" in line) return@forEachIndexed
                if (enclosingScopeIsPrivate(lines, index)) return@forEachIndexed

                assertThat(line)
                    .describedAs("%s:%d is a const val a sibling package can inline", path, index + 1)
                    .isEmpty()
            }
        }
    }

    private fun enclosingScopeIsPrivate(
        lines: List<String>,
        from: Int,
    ): Boolean {
        for (index in from downTo 0) {
            val line = lines[index]
            val declaresScope =
                Regex("""^\s*(private )?companion object""").containsMatchIn(line) ||
                    Regex("""^(internal |private )?object """).containsMatchIn(line)
            if (declaresScope) return "private" in line.substringBefore("object")
        }
        return false
    }

    private companion object {
        val MAY_REDIRECT = setOf("OAuthLoginSuccessHandler.kt", "OAuthLoginFailureHandler.kt")
    }
}
