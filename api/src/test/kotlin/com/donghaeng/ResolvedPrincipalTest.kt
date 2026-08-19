package com.donghaeng

import com.tngtech.archunit.core.domain.JavaAnnotation
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.core.domain.JavaModifier
import com.tngtech.archunit.core.domain.JavaParameter
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.web.bind.annotation.RequestMapping

/**
 * **Forgetting the resolver has to fail closed** — `#5`'s hard acceptance
 * criterion, and the price of the auth gate being the resolver rather than the
 * filter chain (notes/2026-08-10-decision-auth-gate-and-sequence.md).
 *
 * `authorizeHttpRequests` is `permitAll` in every environment, so a handler that
 * declares no resolved principal is open to anonymous callers, and a handler under
 * `/weddings/{weddingId}` that declares no [WEDDING] is open to every logged-in
 * stranger. Nothing goes red for either on its own: the endpoint works, the tests
 * someone wrote for it pass, and the hole is visible only by reading the signature.
 * So the signatures are swept.
 *
 * ## Why a build-time sweep and not the interceptor the record also offered
 *
 * The record allows either — "a `HandlerInterceptor` that denies any handler which
 * has not declared itself public, or a build-time test that every handler method
 * takes a resolved principal" — and shipping neither is not an option. The sweep
 * wins for one structural reason and one operational one.
 *
 * **An interceptor cannot see the second spelling, and this can.** Since `#37` the
 * caller is matched by TYPE, which closed `fun h(caller: AuthenticatedUser)` with
 * the annotation forgotten. What it did not close is the caller ADDING one:
 * `@RequestBody`, `@ModelAttribute`, `@RequestParam`, `@RequestPart` and
 * `@PathVariable` are all resolved by Spring's own blocks BEFORE a custom resolver
 * is asked, so each of those spellings hands the handler a fully-formed identity
 * built out of the request — `?id=42`, or `{"id":42}`, or a nested property on a
 * body class. To a `HandlerInterceptor` that handler has declared a principal and
 * is indistinguishable from a correct one. Only a signature sweep can tell them
 * apart, so the sweep has to exist regardless; adding an interceptor on top would
 * be a second registry of what is public, kept by hand, whose own failure mode is a
 * legitimately public endpoint answering 401 in production.
 *
 * **And an interceptor's reach would have to be cut back to this one anyway.** It
 * sees every handler in the context, including ones we did not write — springdoc's
 * `/v3/api-docs` (generated in the build, `#39`) and the `ERROR` dispatch among
 * them — so it would need its own exemptions for framework handlers, at which point
 * what it covers is exactly what is swept here.
 *
 * The cost is stated rather than hidden: this fires at build time, so it protects
 * `main` through CI rather than the running process. That is the same standing this
 * repository's other gates have, and it is why the merge rule is "a red check is
 * never merged".
 */
class ResolvedPrincipalTest {
    @Test
    fun `the sweep actually reaches the handlers, and sees both kinds of principal`() {
        // Every assertion below is "for each handler", which an empty list
        // satisfies, and a partially narrowed import is the quiet failure — the
        // classpath read is `build/classes`, not the source tree.
        assertThat(handlers().map(::nameOf))
            .contains(
                "AuthController.me",
                "AuthController.logout",
                "WeddingController.create",
                "WeddingController.read",
                "ProblemErrorController.handle",
            )
        assertThat(handlers().filter { principalsOf(it).contains(CALLER) }.map(::nameOf)).contains("AuthController.me")
        assertThat(handlers().filter { principalsOf(it).contains(WEDDING) }.map(::nameOf)).contains("WeddingController.read")
    }

    @Test
    fun `every handler takes a resolved principal, or is named public right here`() {
        val undeclared =
            handlers()
                .filter { principalsOf(it).isEmpty() }
                .map(::nameOf)
                .filterNot { it in PUBLIC }

        assertThat(undeclared)
            .describedAs(
                "Under `permitAll` a handler that declares no resolved principal is reachable by anyone, with no " +
                    "session and no membership — the endpoint works, and nothing else goes red. Take an " +
                    "AuthenticatedUser (who is asking) or a WeddingScope (which wedding), or, if it is genuinely " +
                    "public, add it to PUBLIC below WITH the reason " +
                    "(notes/2026-08-10-decision-auth-gate-and-sequence.md).",
            ).isEmpty()
    }

    @Test
    fun `a handler under a wedding template takes the resolved scope, never the path variable`() {
        val unscoped =
            handlers()
                .filter { handler -> pathsOf(handler).any { WEDDING_TEMPLATE in it } }
                .filterNot { principalsOf(it).contains(WEDDING) }
                .map { "${nameOf(it)} is mapped under $WEDDING_TEMPLATE without taking a WeddingScope" }

        assertThat(unscoped)
            .describedAs(
                "A wedding id read straight out of the path is a wedding id the caller chose. Membership is what " +
                    "makes it theirs, and the resolver is the only thing that checks it " +
                    "(notes/2026-08-17-decision-first-domain-endpoint-shape.md).",
            ).isEmpty()
    }

    @Test
    fun `no handler lets the request supply a principal`() {
        // The second spelling, in its direct form. Each of these annotations is
        // resolved by a Spring block that runs BEFORE any custom resolver, so the
        // handler is handed an identity the caller wrote — and SessionService.resolve
        // is never called. Refused on the TYPE, so a parameter of a principal type
        // may carry no request-binding annotation at all, whichever it is.
        val supplied =
            handlers().flatMap { handler ->
                handler.parameters
                    .filter { it.rawType.name in PRINCIPALS }
                    .flatMap { parameter -> parameter.annotations.map { it.rawType.name } }
                    .filter { it in REQUEST_BINDING }
                    .map { "${nameOf(handler)} takes a resolved principal annotated $it" }
            }

        assertThat(supplied)
            .describedAs(
                "Spring resolves @RequestBody/@ModelAttribute/@RequestParam/@RequestPart/@PathVariable before it " +
                    "asks a custom resolver, so the annotation turns the parameter into request data: `?id=42` " +
                    "becomes the caller. Declare the parameter bare — the type IS the match, and `@CurrentUser` / " +
                    "`@CurrentWedding` are the only annotations it may carry (`#37`).",
            ).isEmpty()
    }

    @Test
    fun `no type the request can bind into declares a principal or a wedding id`() {
        // The same spelling one level down, where a parameter annotation cannot be
        // seen: `data class Req(val caller: AuthenticatedUser, ...)` behind a
        // `@RequestBody` is Jackson populating the identity from the body. Walked
        // transitively from every parameter a request can bind, because the nesting
        // has no depth limit.
        //
        // `weddingId` is refused in the same walk and for the same reason one level
        // out: a wedding id that arrives in a request is a wedding id the caller
        // chose, and the resolver is what makes one trustworthy.
        val smuggled =
            bindingReachableTypes().flatMap { type ->
                type.fields
                    .filterNot { it.modifiers.contains(JavaModifier.SYNTHETIC) }
                    .mapNotNull { field ->
                        when {
                            field.rawType.name in PRINCIPALS -> "${type.simpleName}.${field.name} is a resolved principal"
                            field.name == WEDDING_ID -> "${type.simpleName}.${field.name} carries a wedding id"
                            else -> null
                        }
                    }
            }

        assertThat(smuggled)
            .describedAs(
                "A type reachable from a bound parameter is a type the request fills in. A principal there is the " +
                    "caller declaring who they are; a wedding id there is the caller declaring whose ledger this " +
                    "is. Neither may come from the request (`#5`, `#37`).",
            ).isEmpty()
    }

    @Test
    fun `nothing in this application holds a resolved principal as state`() {
        // Broader than the walk above, and deliberately so: the walk is a piece of
        // code that can be wrong about what it reaches, while this cannot — nothing
        // outside `com.donghaeng` can name our own types, so a complete ban here is
        // complete for the whole classpath. A principal is a value resolved per
        // request; anything that stores one has either extended its lifetime beyond
        // the request or is a request DTO the walk failed to reach.
        val held =
            classes
                .flatMap { it.fields }
                .filterNot { it.modifiers.contains(JavaModifier.SYNTHETIC) }
                .filter { it.rawType.name in PRINCIPALS }
                .map { "${it.owner.simpleName}.${it.name}" }

        assertThat(held)
            .describedAs("A resolved principal is per-request state and belongs in a parameter, never in a field.")
            .isEmpty()
    }

    /**
     * Every type a request can bind a value into, transitively. Starts from the
     * parameters Spring binds from the request — the annotated ones, and any
     * un-annotated application type, which Spring's catch-all
     * `ServletModelAttributeMethodProcessor` populates out of the query string.
     */
    private fun bindingReachableTypes(): Set<JavaClass> {
        val reached = mutableSetOf<JavaClass>()
        val pending = ArrayDeque(handlers().flatMap { it.parameters }.filter(::isRequestBound).map { it.rawType })
        while (pending.isNotEmpty()) {
            val type = pending.removeFirst()
            if (!type.name.startsWith("$ROOT.") || !reached.add(type)) continue
            pending += type.fields.map { it.rawType }
            pending += type.constructors.flatMap { it.rawParameterTypes }
        }
        return reached
    }

    private fun isRequestBound(parameter: JavaParameter): Boolean {
        if (parameter.rawType.name in PRINCIPALS) return false
        if (parameter.annotations.any { it.rawType.name in REQUEST_BINDING }) return true
        return parameter.rawType.name.startsWith("$ROOT.")
    }

    /** Which resolved principals this handler declares, keyed on the TYPE — never on an annotation. */
    private fun principalsOf(handler: JavaMethod): Set<String> =
        handler.parameters
            .map { it.rawType.name }
            .filter { it in PRINCIPALS }
            .toSet()

    /** Its own mapping and the class-level one it inherits — a prefix carries the template just as well. */
    private fun pathsOf(handler: JavaMethod): List<String> = handler.mappings().flatMap { it.strings("value") + it.strings("path") }

    /**
     * A handler is a method that maps something itself. A class-level
     * `@RequestMapping` does not make every method on the class one, which is why
     * [mappings] is not the test here.
     */
    private fun handlers(): List<JavaMethod> =
        classes.flatMap { it.methods }.filter { method -> method.annotations.any { it.rawType.isMapping() } }

    private fun JavaMethod.mappings(): List<JavaAnnotation<*>> = (annotations + owner.annotations).filter { it.rawType.isMapping() }

    private fun JavaClass.isMapping(): Boolean =
        name == RequestMapping::class.java.name ||
            isAnnotatedWith(RequestMapping::class.java) ||
            isMetaAnnotatedWith(RequestMapping::class.java)

    private fun JavaAnnotation<*>.strings(attribute: String): List<String> =
        when (val value = get(attribute).orElse(null)) {
            is Array<*> -> value.filterIsInstance<String>()
            is String -> listOf(value)
            else -> emptyList()
        }

    private fun nameOf(handler: JavaMethod): String = "${handler.owner.simpleName}.${handler.name}"

    private companion object {
        const val ROOT = "com.donghaeng"

        /** Who is asking. */
        const val CALLER = "$ROOT.auth.session.AuthenticatedUser"

        /** Which wedding — resolved from the caller's membership, never from the request. */
        const val WEDDING = "$ROOT.wedding.WeddingScope"

        val PRINCIPALS = setOf(CALLER, WEDDING)

        const val WEDDING_ID = "weddingId"

        const val WEDDING_TEMPLATE = "{$WEDDING_ID}"

        /**
         * Every annotation that makes Spring build a parameter out of the request,
         * each resolved ahead of any custom resolver.
         */
        val REQUEST_BINDING =
            setOf(
                "org.springframework.web.bind.annotation.RequestBody",
                "org.springframework.web.bind.annotation.ModelAttribute",
                "org.springframework.web.bind.annotation.RequestParam",
                "org.springframework.web.bind.annotation.RequestPart",
                "org.springframework.web.bind.annotation.PathVariable",
            )

        /**
         * The handlers that take no principal ON PURPOSE. Each needs a reason, and
         * "it does not need one" is not one — the question is what a stranger can do
         * with it.
         *
         * - `AuthController.logout` answers 204 whatever it finds, deliberately: a
         *   logout that can fail is one a client writes error handling for, and that
         *   is how a sign-out button ends up leaving people signed in. A stranger
         *   calling it ends nothing, because it acts only on the token the request
         *   itself carries.
         * - `ProblemErrorController.handle` IS the error dispatch. Demanding a
         *   session there would answer 401 to every anonymous 404 in the API, and
         *   demanding one while producing a 401 is a loop.
         */
        val PUBLIC = setOf("AuthController.logout", "ProblemErrorController.handle")

        val classes: JavaClasses =
            ClassFileImporter()
                .withImportOption(ImportOption.DoNotIncludeTests())
                .importPackages(ROOT)
    }
}
