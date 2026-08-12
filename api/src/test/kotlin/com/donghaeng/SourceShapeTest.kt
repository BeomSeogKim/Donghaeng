package com.donghaeng

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence

/**
 * File shape, checked rather than described.
 *
 * `api/AGENTS.md` used to carry these as prose, and the rules-about-rules say a
 * mechanically checkable rule belongs in the check and then *not* also in the rule
 * file. This is the check, so the prose is gone.
 *
 * It reads the SOURCE tree, which [ArchitectureTest] deliberately does not — file
 * names and declaration counts do not survive compilation, so bytecode cannot see
 * them. That is also why this is a hand-written scan rather than another ArchUnit
 * rule; the alternative was a second architecture-testing dependency (Konsist) for
 * two assertions.
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
        // Both assertions below are "for each file", which an empty list satisfies.
        assertThat(sources().map { it.fileName.toString() })
            .contains("AppUser.kt", "AppUserRepository.kt", "SessionService.kt")
    }

    @Test
    fun `a repository is the only declaration in its file`() {
        // The layer-bucket prohibition at its smallest scale: `AuthRepositories.kt`
        // held three, and an entity file holding its own repository was the same
        // mistake one step quieter. A repository is the layer boundary and gets its
        // own file — the shape JetBrains publishes for Spring.
        sources().forEach { path ->
            val declared = declarationsIn(path)
            if (declared.none { it.endsWith("Repository") }) return@forEach

            assertThat(declared)
                .describedAs("%s declares a repository alongside %s", path, declared)
                .hasSize(1)
        }
    }

    @Test
    fun `a file declaring one type is named after it`() {
        // Kotlin's convention allows several declarations in a file as long as they
        // are closely related AND the file name says what it contains. The second
        // half is what a two-type file named after one of them was quietly failing.
        // Only the single-declaration case is checkable, so only it is checked; a
        // multi-declaration file's name stays a judgement.
        sources().forEach { path ->
            val declared = declarationsIn(path)
            if (declared.size != 1) return@forEach

            assertThat(path.fileName.toString())
                .describedAs("%s declares only %s", path, declared.single())
                .isEqualTo("${declared.single()}.kt")
        }
    }
}
