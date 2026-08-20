package com.donghaeng

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.web.bind.annotation.RequestMapping

/**
 * **A handler that needs a caller takes the caller first.**
 *
 * Spring resolves handler arguments in declaration order, so the position decides
 * what an anonymous request is answered with: caller first and it is a 401; body
 * first and Bean Validation runs before anything asks who is calling, so an
 * anonymous caller gets a 400 that tells them which fields the endpoint has and
 * which values it refuses. Nothing else notices — the request is rejected either
 * way, with the right status for the wrong reason.
 *
 * A per-endpoint test can only cover the endpoints someone remembered to write one
 * for, and there are fifteen handlers to come. So it is swept, like the content-type
 * gate next door.
 *
 * Since `#5` there is a second resolved parameter with the same property —
 * `WeddingScope`, which fails a request that gets past the session with a 404. The
 * rule generalises: **every parameter a resolver produces comes before every
 * parameter the request supplies**, because the first parameter that fails decides
 * the answer.
 */
class CurrentUserParameterTest {
    @Test
    fun `the sweep actually reaches the handlers that take a caller`() {
        // Every assertion below is "for each handler", which an empty list satisfies.
        // The classpath read is `build/classes`, not the source tree.
        assertThat(handlersTakingACaller().map(::nameOf))
            .contains("AuthController.me", "WeddingController.create")
    }

    @Test
    fun `a resolved caller is the first parameter of its handler`() {
        val misplaced =
            handlersTakingACaller()
                .filterNot { handler -> callerIndex(handler) == 0 }
                .map { "${nameOf(it)} takes a resolved caller at position ${callerIndex(it)}" }

        assertThat(misplaced)
            .describedAs(
                "A handler whose body parameter comes first validates the body BEFORE the session is resolved, so an " +
                    "anonymous request is answered 400 with the endpoint's own validation messages instead of 401. " +
                    "Declare the caller first (notes/2026-08-10-decision-auth-gate-and-sequence.md).",
            ).isEmpty()
    }

    @Test
    fun `no resolved parameter comes after one the request supplies`() {
        val misordered =
            handlers().mapNotNull { handler ->
                val types = handler.parameters.map { it.type.name }
                val lastResolved = types.indexOfLast { it in RESOLVED }
                val firstSupplied = types.indexOfFirst { it !in RESOLVED }
                if (firstSupplied in 0..<lastResolved) {
                    "${nameOf(handler)} resolves at $lastResolved, after a request-supplied parameter at $firstSupplied"
                } else {
                    null
                }
            }

        assertThat(misordered)
            .describedAs(
                "Argument resolution is declaration order, and the first parameter that fails decides the answer. A " +
                    "WeddingScope declared after the body validates a stranger's request before it finds out the " +
                    "wedding is not theirs — a 400 listing the endpoint's fields where the contract says 404 " +
                    "(notes/2026-08-10-decision-auth-gate-and-sequence.md).",
            ).isEmpty()
    }

    private fun callerIndex(handler: JavaMethod): Int = handler.parameters.indexOfFirst { it.type.name == CALLER }

    private fun handlersTakingACaller(): List<JavaMethod> = handlers().filter { callerIndex(it) >= 0 }

    private fun nameOf(handler: JavaMethod): String = "${handler.owner.simpleName}.${handler.name}"

    private fun handlers(): List<JavaMethod> =
        classes
            .flatMap { it.methods }
            .filter { method -> method.annotations.any { it.rawType.isMapping() } }

    private fun JavaClass.isMapping(): Boolean =
        name == RequestMapping::class.java.name ||
            isAnnotatedWith(RequestMapping::class.java) ||
            isMetaAnnotatedWith(RequestMapping::class.java)

    private companion object {
        /**
         * The TYPE, not `@CurrentUser`: the resolver matches on the type and the
         * annotation is optional documentation, so a rule keyed on the annotation
         * would miss exactly the handler that forgot it — which is the one this
         * exists for.
         */
        const val CALLER = "com.donghaeng.auth.session.AuthenticatedUser"

        /** Which wedding, resolved from the caller's membership — `#5`'s parameter. */
        const val WEDDING = "com.donghaeng.wedding.WeddingScope"

        val RESOLVED = setOf(CALLER, WEDDING)

        val classes: JavaClasses =
            ClassFileImporter()
                .withImportOption(ImportOption.DoNotIncludeTests())
                .importPackages("com.donghaeng")
    }
}
