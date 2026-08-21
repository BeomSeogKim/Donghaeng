package com.donghaeng

import com.donghaeng.json.NotCleared
import com.donghaeng.json.Patch
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaField
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * **Whether a member can be cleared is decided, never left to attention.**
 *
 * A [Patch] member sent as `null` reaches its service as [Patch.Cleared], and what
 * refuses that on a member backed by a NOT NULL column is [NotCleared]. Forgetting
 * it **fails silent, not closed**, which is why this file exists: the `when` in a
 * service reads `Patch.Cleared -> Unit`, the request answers **200 having written
 * nothing**, and no test anywhere notices that the API accepted a clear it did not
 * perform. The author who instead writes `Cleared -> row.x = null` gets a masked 500
 * from the column.
 *
 * So every `Patch` member is either annotated or named in [CLEARABLE] **with the
 * reason it may be emptied** — a diff a reviewer sees, in the shape
 * `ResolvedPrincipalTest.PUBLIC` and `ScopelessWeddingEndpointTest.SCOPELESS`
 * already use. `#12` brings five more members and inherits this.
 *
 * It sweeps **every** `Patch` field in the application rather than the ones reachable
 * from a `@RequestBody`, which is both simpler and stricter: a gate that has to
 * discover handlers is a gate that fails open on the handler it could not see.
 */
class PatchMemberSweepTest {
    @Test
    fun `the sweep actually finds the patch members`() {
        // Every assertion below is "for each member", which an empty list satisfies.
        assertThat(patchMembers().map(::nameOf))
            .contains("UpdateWeddingRequest.weddingDate", "UpdateWeddingRequest.guaranteedHeadcount")
    }

    @Test
    fun `every patch member either refuses a clear or says why it allows one`() {
        val undecided =
            patchMembers()
                .filterNot { it.isAnnotatedWith(NotCleared::class.java) }
                .map(::nameOf)
                .filterNot { it in CLEARABLE }

        assertThat(undecided)
            .describedAs(
                "A Patch member with neither @NotCleared nor an entry in CLEARABLE answers 200 to a `null` it then " +
                    "does not act on — a contract lie nothing else catches. Annotate it, or name it below with the " +
                    "domain reason its value can be taken away " +
                    "(notes/2026-08-22-decision-partial-update-shape.md §2).",
            ).isEmpty()
    }

    @Test
    fun `the allowlist carries a reason, and carries no member that has gone`() {
        assertThat(CLEARABLE.filterValues { it.isBlank() }).describedAs("an allowlist entry with no reason is not a decision").isEmpty()
        assertThat(
            CLEARABLE.keys,
        ).describedAs("a stale entry silently exempts nothing, and hides that").isSubsetOf(patchMembers().map(::nameOf))
    }

    private fun patchMembers(): List<JavaField> = classes.flatMap { it.fields }.filter { it.rawType.name == Patch::class.java.name }

    /**
     * `$`-qualified, not the simple name: a member on a NESTED DTO would otherwise
     * read as `Inner.x` with nothing saying whose, and two nested classes sharing a
     * simple name would collide in [CLEARABLE] — an allowlist entry silently
     * exempting a member nobody wrote it for.
     */
    private fun nameOf(field: JavaField): String = "${field.owner.name.substringAfterLast('.')}.${field.name}"

    private companion object {
        /**
         * Members a caller may empty, and why. **The reason is the point** — "it is
         * nullable in the database" is not one, since that is how the column stores
         * the state rather than why the state exists.
         */
        val CLEARABLE =
            mapOf(
                "UpdateWeddingRequest.guaranteedHeadcount" to
                    "보증인원 미설정은 실제 상태다: 예식장과 계약하기 전이거나, 계약이 깨져 다시 그 상태로 " +
                    "돌아간 커플이 있다. 예식장의 숫자를 우리가 대신 들고 있을 수는 없다.",
            )

        val classes: JavaClasses =
            ClassFileImporter()
                .withImportOption(ImportOption.DoNotIncludeTests())
                .importPackages("com.donghaeng")
    }
}
