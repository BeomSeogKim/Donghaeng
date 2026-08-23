package com.donghaeng

import com.donghaeng.auth.StubGoogleRegistration
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

/**
 * **A wedding endpoint that takes no `WeddingScope` is allowed here and nowhere
 * else.** `#132`'s half of the gate.
 *
 * `ResolvedPrincipalTest` refuses a handler under `/weddings/{weddingId}` that does
 * not take a [com.donghaeng.wedding.WeddingScope], and that rule is keyed on the
 * path variable — so it does not reach `POST /weddings` or `GET /weddings`, which
 * have none. Neither of those is *exempted* by it either; they simply fall outside
 * what it can see, and "a wedding endpoint with no scope" is precisely the shape
 * that rule exists to refuse. Falling outside a gate is not the same as being let
 * through it, and the difference has to be written down somewhere a person will
 * read.
 *
 * So it is written down here, as a list with a reason each. The property cannot be
 * inherited by accident: a new handler mapped anywhere under `/weddings` is red until
 * somebody adds its name to [SCOPELESS] and says why, in a diff a reviewer sees.
 * Copying `WeddingController.list`'s signature does not copy its exemption, which is
 * the whole reason the list lives in a test rather than in an annotation a copy-paste
 * would carry along.
 *
 * **It became a list of three on 2026-08-22 (`#181`), which is what that was for.** The
 * invite accept cannot be scoped — the caller holds no seat yet — so the exemption was
 * argued and written rather than assumed, and the endpoint was put under `/weddings`
 * precisely so this gate could see it. A path outside `/weddings` would have escaped
 * the sweep entirely, which is the failure the paragraph above names.
 *
 * **The second half of the rule is that a scopeless wedding endpoint still takes a
 * caller.** Under `permitAll` an endpoint with neither principal is an anonymous
 * one, and the ways out of that are deliberately two allowlists in two files: this
 * one and `ResolvedPrincipalTest.PUBLIC`. Nothing about a ledger belongs behind
 * either alone.
 *
 * It reads [RequestMappingHandlerMapping] rather than class files for the reason
 * `#5` earned the hard way (notes/2026-08-19-decision-wedding-scope-gate.md §1): a
 * class-level `@RequestMapping("/weddings")` on a base controller is invisible to a
 * private model of Spring's mapping, and a gate that cannot see a handler fails
 * open. This lives beside that sweep rather than inside it only because `#5` was
 * still under review when it was written.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(StubGoogleRegistration::class)
internal class ScopelessWeddingEndpointTest {
    @Autowired private lateinit var mappings: RequestMappingHandlerMapping

    @Test
    fun `the sweep reaches the wedding endpoints`() {
        // Both assertions below are "for each handler", which an empty list
        // satisfies, and the production-only narrowing is what could empty it.
        assertThat(weddingHandlers().map(::nameOf))
            .contains("WeddingController.create", "WeddingController.read", "WeddingController.list")
    }

    @Test
    fun `a wedding endpoint takes a resolved scope, or is named scopeless right here`() {
        val unlisted =
            weddingHandlers()
                .filterNot { principalsOf(it).contains(WEDDING) }
                .map(::nameOf)
                .filterNot { it in SCOPELESS }

        assertThat(unlisted)
            .describedAs(
                "A handler under `/weddings` that takes no WeddingScope is a wedding read or write that nothing " +
                    "scoped: the seat walk is the only thing that decides whose ledger this is, and no " +
                    "`{weddingId}` in the path means `ResolvedPrincipalTest` cannot ask for it. Take a WeddingScope " +
                    "— or, if the endpoint genuinely answers from the session alone, add it to SCOPELESS below WITH " +
                    "the reason (notes/2026-08-20-decision-listing-the-callers-weddings.md).",
            ).isEmpty()
    }

    @Test
    fun `a scopeless wedding endpoint still takes a caller`() {
        val anonymous =
            weddingHandlers()
                .filter { nameOf(it) in SCOPELESS }
                .filterNot { principalsOf(it).contains(CALLER) }
                .map(::nameOf)

        assertThat(anonymous)
            .describedAs(
                "`authorizeHttpRequests` is permitAll in every environment, so a scopeless endpoint that also takes " +
                    "no AuthenticatedUser is an anonymous one — and the session is the only scope these two have " +
                    "(notes/2026-08-10-decision-auth-gate-and-sequence.md).",
            ).isEmpty()
    }

    /** What this application serves under `/weddings`, minus the test classpath's own controllers. */
    private fun weddingHandlers(): List<HandlerMethod> =
        mappings.handlerMethods.values
            .filter { it.beanType.name in productionClassNames }
            .distinctBy { it.method }
            .filter { handler -> pathsOf(handler).any { it == WEDDINGS || it.startsWith("$WEDDINGS/") } }

    /** Every path Spring serves this handler at, prefix and hierarchy already resolved. */
    private fun pathsOf(handler: HandlerMethod): Set<String> =
        mappings.handlerMethods
            .filterValues { it.method == handler.method }
            .keys
            .flatMap { it.pathPatternsCondition?.patternValues ?: it.patternsCondition?.patterns.orEmpty() }
            .toSet()

    /** Keyed on the TYPE, never on `@CurrentWedding` — a rule keyed on the annotation misses the handler that forgot it. */
    private fun principalsOf(handler: HandlerMethod): Set<String> = handler.methodParameters.map { it.parameterType.name }.toSet()

    private fun nameOf(handler: HandlerMethod): String = "${handler.beanType.simpleName}.${handler.method.name}"

    private companion object {
        const val WEDDINGS = "/weddings"

        const val CALLER = "com.donghaeng.auth.session.AuthenticatedUser"

        const val WEDDING = "com.donghaeng.wedding.WeddingScope"

        /**
         * The wedding endpoints that answer from the session alone. Each needs a
         * reason, and "it has no `{weddingId}` to resolve" is a restatement rather
         * than one — the question is what the caller could reach that is not theirs.
         *
         * - `WeddingController.create` makes the caller's first seat, so there is
         *   nothing to resolve until it has run. It reads no existing wedding and
         *   writes only rows it creates.
         * - `WeddingController.list` answers "which weddings are the caller's", which
         *   is the question a client asks BEFORE it has an id (`#132`) — for the
         *   "최초 1회" branch and for the ledger to survive a refresh. The seat
         *   join IS its scope: it can only ever return rows the resolver would have
         *   accepted one at a time, and `WeddingListContractTest` is what holds that.
         * - `WeddingInviteController.join` is `#181`'s accept, and it is the design
         *   change the previous version of this comment said a third entry would be.
         *   **The caller is not a member yet** — that is what the request is for — so
         *   there is no seat for the resolver to walk. **The token is what stands in
         *   for the scope**: 256 bits of CSPRNG, single use, one day, compared against
         *   a stored hash in constant time, and it names the seat rather than being
         *   handed one (notes/2026-08-22-decision-the-invite-link.md,
         *   notes/2026-08-22-decision-the-partner-invite.md). It reads nothing: every
         *   refusal answers the same document, and the only row it writes is the seat
         *   that token identifies. `AcceptInviteContractTest` is what holds that.
         *
         * - `WeddingInviteController.preview` is `#214`'s pre-accept read, and it is
         *   the join's own exemption seen from one step earlier: the caller is not a
         *   member yet, and **the token is what stands in for the scope** — the same
         *   token, parsed and compared by the same code, refused in the same order.
         *   It writes nothing and spends nothing, and it publishes 결혼식 이름, 예식일
         *   and the inviting seat's name — **no wedding id**, so nothing it answers
         *   can be carried to a scoped endpoint
         *   (notes/2026-08-23-decision-the-wedding-has-a-name.md).
         *   `PreviewInviteContractTest` is what holds that.
         *
         * A fifth entry is a design change, not a line: any endpoint that reads or
         * writes a wedding's CONTENTS has a wedding in mind, and the id belongs in
         * the path where the resolver can check it.
         */
        val SCOPELESS =
            setOf(
                "WeddingController.create",
                "WeddingController.list",
                "WeddingInviteController.join",
                "WeddingInviteController.preview",
            )

        val productionClassNames: Set<String> =
            ClassFileImporter()
                .withImportOption(ImportOption.DoNotIncludeTests())
                .importPackages("com.donghaeng")
                .map { it.name }
                .toSet()

        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) = SharedPostgres.publish(registry)
    }
}
