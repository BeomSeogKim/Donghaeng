package com.donghaeng

import com.donghaeng.auth.StubGoogleRegistration
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaModifier
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.MethodParameter
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.mvc.method.RequestMappingInfo
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.lang.reflect.GenericArrayType
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType

/**
 * **Forgetting the resolver has to fail closed** — `#5`'s hard acceptance
 * criterion, and the price of the auth gate being the resolver rather than the
 * filter chain (notes/2026-08-10-decision-auth-gate-and-sequence.md).
 *
 * `authorizeHttpRequests` is `permitAll` in every environment, so a handler that
 * declares no resolved principal is open to anonymous callers, and a handler under
 * `/weddings/{weddingId}` that declares no [WeddingScope] is open to every logged-in
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
 * The cost is stated rather than hidden: this fires at build time, so it protects
 * `main` through CI rather than the running process. That is the same standing this
 * repository's other gates have, and it is why the merge rule is "a red check is
 * never merged".
 *
 * ## Why it boots the application instead of reading class files
 *
 * The first version read bytecode with ArchUnit and modelled Spring's mapping rules
 * itself. **Two handlers that Spring serves were invisible to that model**, both
 * found by writing them and watching the suite stay green:
 *
 * - a class-level `@RequestMapping("/weddings/{weddingId}")` **on an abstract base
 *   controller**, since annotations were read from the declaring class only. A
 *   shared base is the ordinary way sibling endpoints get factored, so this was the
 *   likeliest of the two to happen for real.
 * - `@GetMapping("/weddings/{id}/…")`, since the check was a substring test for the
 *   literal `{weddingId}`. Spring maps and serves it; the resolver never sees a
 *   variable by that name, and the handler reads the id straight from the path.
 *
 * Both are the same mistake — **a private model of which requests reach which
 * method**. So the rules below run against
 * [RequestMappingHandlerMapping.getHandlerMethods], which IS that mapping, resolved
 * by Spring through the type hierarchy and every composed annotation. Nothing here
 * emulates Spring any more, which is the only version of this that can be trusted
 * as the gate.
 *
 * It costs a context, and the context serves the test classpath's own controllers
 * as well as the application's — so the swept set is narrowed to the classes that
 * ship, by name, from the same production-only import ArchUnit uses.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(StubGoogleRegistration::class)
internal class ResolvedPrincipalTest {
    @Autowired private lateinit var mappings: RequestMappingHandlerMapping

    @Test
    fun `the sweep reaches what the application serves, and sees both kinds of principal`() {
        // Every assertion below is "for each handler", which an empty list
        // satisfies, and the production-only narrowing is exactly the thing that
        // could quietly empty it.
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

        // The paths are read off the mapping, not off the annotation, so the
        // inherited-prefix case is covered by construction rather than by a rule.
        assertThat(pathsOf(handlers().single { nameOf(it) == "WeddingController.read" })).contains("/weddings/{weddingId}")
    }

    @Test
    fun `nothing compiled from test source is mapped into a context that did not ask for it`() {
        // The narrowing above is a filter on a set that should not need one, and a
        // filter is how a leak stops being visible: `handlers()` drops a test
        // controller silently, and every rule in this class then passes over an
        // endpoint the application is really serving. So the un-narrowed set is
        // asserted here, once, and this is the assertion that `#118` is closed by.
        //
        // Not vacuous by construction: this test class is itself compiled to the
        // test output, so a detector that had stopped recognising it — a Gradle
        // layout change, a jar-packaged test classpath — goes red rather than quiet.
        assertThat(compiledFromTestSource(javaClass))
            .describedAs("%s must be recognisable as test source, or the sweep below asserts nothing", javaClass.name)
            .isTrue()

        val leaked =
            mappings.handlerMethods
                .filterValues { compiledFromTestSource(it.beanType) }
                .map { (info, handler) -> "${handler.beanType.name} maps $info" }

        assertThat(leaked)
            .describedAs(
                "The component scan is rooted at `com.donghaeng` and the test classes are on that classpath, so a " +
                    "`@RestController` declared in test source maps into EVERY @SpringBootTest context — this one, " +
                    "and the one OpenApiDocumentTest publishes to `web/` as the API. This context imports no " +
                    "controller, so it should serve none. Annotate the fixture `@TestComponent`, which Boot's " +
                    "TypeExcludeFilter skips during the scan, and `@Import` it back in the one test that needs it " +
                    "(`#118`).",
            ).isEmpty()
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
    fun `a wedding path names its variable weddingId, and its handler takes the resolved scope`() {
        val unscoped =
            handlers().flatMap { handler ->
                pathsOf(handler).mapNotNull { path ->
                    val named = weddingVariableOf(path)
                    when {
                        // The rename. `/weddings/{id}` is mapped and served, and the
                        // resolver looks up `weddingId` and finds nothing — so the id
                        // in the path is one nobody checked the membership for.
                        named != null && named != WEDDING_ID ->
                            "${nameOf(handler)} maps $path, whose wedding variable is named `$named` and not `$WEDDING_ID`"
                        // The omission, wherever the template sits in the path.
                        "{$WEDDING_ID}" in path && !principalsOf(handler).contains(WEDDING) ->
                            "${nameOf(handler)} maps $path without taking a WeddingScope"
                        else -> null
                    }
                }
            }

        assertThat(unscoped)
            .describedAs(
                "A wedding id read straight out of the path is a wedding id the caller chose; membership is what " +
                    "makes it theirs, and the resolver is the only thing that checks it. The NAME is half the rule " +
                    "because the resolver reads the variable by name — see WEDDING_ID_VARIABLE in " +
                    "CurrentWeddingResolution.kt (notes/2026-08-19-decision-wedding-scope-gate.md).",
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
                handler.methodParameters
                    .filter { it.parameterType.name in PRINCIPALS }
                    .flatMap { parameter -> parameter.parameterAnnotations.map { it.annotationClass.java.name } }
                    .filter { it in REQUEST_BINDING }
                    .map { "${nameOf(handler)} takes a resolved principal annotated $it" }
            }

        assertThat(supplied)
            .describedAs(
                "Spring resolves a request-binding annotation before it asks a custom resolver, so the annotation " +
                    "turns the parameter into request data: `?id=42` becomes the caller. Declare the parameter " +
                    "bare — the type IS the match, and `@CurrentUser` / `@CurrentWedding` are the only annotations " +
                    "it may carry (`#37`).",
            ).isEmpty()
    }

    @Test
    fun `no type the request can bind into reaches a principal or declares a wedding id`() {
        // The same spelling one level down, where a parameter annotation cannot be
        // seen: `data class Req(val caller: AuthenticatedUser, ...)` behind a
        // `@RequestBody` is Jackson populating the identity from the body.
        //
        // Walked through GENERIC types, not raw ones: `List<Row>` inside a body is
        // the shape the import and vendor-email intakes will send, and a walk that
        // erased the type argument would pass a `Row` it would have refused on its
        // own.
        val smuggled =
            bindingReachableTypes().flatMap { type ->
                val reachedPrincipal =
                    if (type.name in PRINCIPALS) listOf("${type.simpleName} is reachable from a bound parameter") else emptyList()
                reachedPrincipal +
                    type.declaredFields
                        .filter { it.name == WEDDING_ID }
                        .map { "${type.simpleName}.${it.name} carries a wedding id" }
            }

        assertThat(smuggled)
            .describedAs(
                "A type reachable from a bound parameter is a type the request fills in. A principal there is the " +
                    "caller declaring who they are; a wedding id there is the caller declaring whose ledger this " +
                    "is. Neither may come from the request (`#5`, `#37`), and `docs/api-spec.md` promises `web/` " +
                    "that the wedding id travels in the path and nowhere else.",
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
            productionClasses
                .flatMap { it.fields }
                .filterNot { it.modifiers.contains(JavaModifier.SYNTHETIC) }
                .filter { it.rawType.name in PRINCIPALS }
                .map { "${it.owner.simpleName}.${it.name}" }

        assertThat(held)
            .describedAs("A resolved principal is per-request state and belongs in a parameter, never in a field.")
            .isEmpty()
    }

    /**
     * What this application serves, minus everything else the context maps —
     * springdoc's and Boot's own handlers, and any controller the test classpath
     * leaked in, which the scan would pick up as readily as the application's own.
     * Narrowed by production class NAME rather than by package, so a test controller
     * that shares a package is still excluded — and that leak is separately forbidden
     * rather than merely filtered, because a filter is what would hide it (`#118`).
     */
    private fun handlers(): List<HandlerMethod> =
        mappings.handlerMethods.values
            .filter { it.beanType.name in productionClassNames }
            .distinctBy { it.method }

    /**
     * Where the class file came from, which is the only thing that distinguishes a
     * fixture from an endpoint: a test controller may sit in a shipped package, carry
     * no test-only annotation once the scan has registered it, and is otherwise an
     * ordinary bean. Asked of the code source rather than of the production name set
     * `handlers()` uses, so a third-party handler — springdoc's, Boot's — is neither
     * counted as a leak nor mistaken for ours.
     */
    private fun compiledFromTestSource(type: Class<*>): Boolean =
        type.protectionDomain
            ?.codeSource
            ?.location
            ?.path
            ?.let(TEST_OUTPUT::containsMatchIn) == true

    /** Every path Spring serves this handler at, prefix and hierarchy already resolved. */
    private fun pathsOf(handler: HandlerMethod): Set<String> =
        mappings.handlerMethods
            .filterValues { it.method == handler.method }
            .keys
            .flatMap(::patternsOf)
            .toSet()

    private fun patternsOf(info: RequestMappingInfo): Set<String> =
        info.pathPatternsCondition?.patternValues ?: info.patternsCondition?.patterns.orEmpty()

    /**
     * The variable a wedding path opens with, or `null` when the path is not one.
     * `{weddingId:\\d+}` names `weddingId`; the regex suffix is part of the pattern
     * syntax, not of the name.
     */
    private fun weddingVariableOf(path: String): String? =
        WEDDING_PATH
            .find(path)
            ?.groupValues
            ?.get(1)
            ?.substringBefore(':')

    /** Which resolved principals this handler declares, keyed on the TYPE — never on an annotation. */
    private fun principalsOf(handler: HandlerMethod): Set<String> =
        handler.methodParameters
            .map { it.parameterType.name }
            .filter { it in PRINCIPALS }
            .toSet()

    private fun nameOf(handler: HandlerMethod): String = "${handler.beanType.simpleName}.${handler.method.name}"

    /**
     * Every type a request can bind a value into, transitively and through type
     * arguments. Starts from the parameters Spring binds from the request — the
     * annotated ones, and any un-annotated application type, which Spring's catch-all
     * `ServletModelAttributeMethodProcessor` populates out of the query string.
     */
    private fun bindingReachableTypes(): Set<Class<*>> {
        val reached = mutableSetOf<Class<*>>()
        val pending = ArrayDeque<Type>()
        handlers()
            .flatMap { it.methodParameters.toList() }
            .filter(::isRequestBound)
            .forEach { pending += it.genericParameterType }

        while (pending.isNotEmpty()) {
            rawTypesOf(pending.removeFirst()).forEach { raw ->
                if (!raw.name.startsWith("$ROOT.") || !reached.add(raw)) return@forEach
                raw.declaredFields.forEach { pending += it.genericType }
                raw.declaredConstructors.forEach { pending += it.genericParameterTypes.toList() }
            }
        }
        return reached
    }

    /** The classes a declared type can actually hold: itself, its type arguments, its bounds. */
    private fun rawTypesOf(type: Type): List<Class<*>> =
        when (type) {
            is Class<*> -> if (type.isArray) rawTypesOf(type.componentType) else listOf(type)
            is ParameterizedType -> rawTypesOf(type.rawType) + type.actualTypeArguments.flatMap(::rawTypesOf)
            is GenericArrayType -> rawTypesOf(type.genericComponentType)
            is WildcardType -> (type.upperBounds + type.lowerBounds).flatMap(::rawTypesOf)
            is TypeVariable<*> -> type.bounds.flatMap(::rawTypesOf)
            else -> emptyList()
        }

    private fun isRequestBound(parameter: MethodParameter): Boolean {
        if (parameter.parameterType.name in PRINCIPALS) return false
        if (parameter.parameterAnnotations.any { it.annotationClass.java.name in REQUEST_BINDING }) return true
        return parameter.parameterType.name.startsWith("$ROOT.")
    }

    private companion object {
        const val ROOT = "com.donghaeng"

        /** Who is asking. */
        const val CALLER = "$ROOT.auth.session.AuthenticatedUser"

        /** Which wedding — resolved from the caller's membership, never from the request. */
        const val WEDDING = "$ROOT.wedding.WeddingScope"

        val PRINCIPALS = setOf(CALLER, WEDDING)

        const val WEDDING_ID = "weddingId"

        val WEDDING_PATH = Regex("""^/weddings/\{([^}]+)}""")

        /**
         * The test source set's compiler output. Deliberately the same three layouts
         * `ImportOption.DoNotIncludeTests` matches on — Gradle, Maven, IntelliJ — since
         * both notions of "this class is a test class" are load-bearing in this file
         * and one recognising a location the other does not is a silent gap.
         */
        val TEST_OUTPUT = Regex("""/(build/classes/([^/]+/)?test|target/test-classes|out/test)/""")

        /**
         * Every annotation that makes Spring build a parameter out of the request,
         * each resolved ahead of any custom resolver.
         *
         * The last three are not reachable as an impersonation today — there is no
         * converter from a header or a cookie to a principal, so the attempt is a 500
         * rather than a forged identity. They are here because this list is what
         * fifteen endpoints will trust, and "everything the request supplies" has to
         * mean it.
         */
        val REQUEST_BINDING =
            setOf(
                "org.springframework.web.bind.annotation.RequestBody",
                "org.springframework.web.bind.annotation.ModelAttribute",
                "org.springframework.web.bind.annotation.RequestParam",
                "org.springframework.web.bind.annotation.RequestPart",
                "org.springframework.web.bind.annotation.PathVariable",
                "org.springframework.web.bind.annotation.RequestHeader",
                "org.springframework.web.bind.annotation.CookieValue",
                "org.springframework.web.bind.annotation.MatrixVariable",
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

        val productionClasses: JavaClasses =
            ClassFileImporter()
                .withImportOption(ImportOption.DoNotIncludeTests())
                .importPackages(ROOT)

        val productionClassNames: Set<String> = productionClasses.map { it.name }.toSet()

        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) = SharedPostgres.publish(registry)
    }
}
