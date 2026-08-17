package com.donghaeng

import com.tngtech.archunit.core.domain.JavaAnnotation
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.JavaEnumConstant
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.web.bind.annotation.RequestMapping

/**
 * v1's CSRF gate, held as a check rather than as prose
 * (`notes/2026-08-13-decision-static-front-and-content-type-gate.md`).
 *
 * `SameSite=Lax` is a *site* control and a sibling host under our registrable
 * domain is same-site with the API, so Lax does not close cross-site writes. What
 * does is the CORS preflight, which a JSON body forces and a CORS-safelisted
 * content type skips. The rule is therefore **positive**: every state-changing
 * handler declares what it consumes, and what it declares must be something a
 * preflight-free request cannot present.
 *
 * ## Why positive, and not just a ban on three strings
 *
 * A ban only reaches a handler that reads a body. `logout` reads none — no
 * `@RequestBody`, and originally no `consumes` — so a `text/plain` simple POST from
 * a sibling host matched its mapping and signed the couple out, while satisfying a
 * check that only looked at declared media types. **`consumes` closes that because
 * it is a mapping condition, not a message-converter concern**: it is evaluated
 * during handler selection, so an unsatisfying request never enters the method.
 * Observed rather than assumed — see `ContentTypeGateContractTest`, which is the
 * other half of this rule and asserts what the declaration does to a live request.
 *
 * ## `application/octet-stream` is banned too, and that is not obvious
 *
 * **A request with no `Content-Type` header at all is matched as
 * `application/octet-stream`** (`ConsumesRequestCondition`), and a `fetch` with a
 * typeless body sends no `Content-Type` and is a CORS *simple* request. So
 * `consumes = application/octet-stream` is preflight-reachable and closes nothing —
 * verified by declaring it on `logout` and watching a header-less POST answer 204.
 * Any other non-safelisted type (`application/json`, `text/csv`, a vendor type) does
 * force a preflight.
 *
 * ## Two ways to declare a `consumes` that does not bind
 *
 * Both pass a naive reading of "it declares one", and both were verified against
 * the running server before these checks were written.
 *
 * **A negated expression widens the condition, it never narrows it.** Matching is
 * an OR over expressions and `ConsumeMediaTypeExpression.match` is
 * `!isNegated() == mediaTypeMatches`, so `!multipart/form-data` *matches* every type
 * that is not multipart — `text/plain` included. `consumes = ["!application/json"]`
 * therefore admits everything except JSON, and even
 * `consumes = ["application/json", "!multipart/form-data"]` admits `text/plain`
 * through its second expression while looking well-formed. **So a negation is
 * refused outright here, not merely required to keep company with a positive
 * expression** — that weaker rule passes the mixed case, which was observed
 * answering 204 to a `text/plain` POST.
 *
 * **`@RequestBody(required = false)` switches the condition off entirely.**
 * `ConsumesRequestCondition.getMatchingCondition` returns `EMPTY_CONDITION` — a
 * match — when `!hasBody(request) && !bodyRequired`, and `bodyRequired` is lowered
 * by exactly one thing: `RequestMappingHandlerMapping.updateConsumesCondition`
 * reading that annotation. A handler with a correct `consumes` and an optional body
 * is reachable body-less and unpreflighted; observed answering 204.
 *
 * ## Scope
 *
 * `POST`, `PUT` and `PATCH` must declare. **`GET` is out** — the standing rule is
 * that no GET changes state, and `/auth/me` declaring a request content type would
 * be nonsense. `DELETE` is out for a different reason worth knowing: only `GET`,
 * `HEAD` and `POST` are CORS-*simple* methods, so `DELETE` (and in truth `PUT` and
 * `PATCH`) always preflight whatever they send. `POST` is the one that strictly
 * needs this; `PUT` and `PATCH` are included anyway so nobody has to remember which
 * method is simple.
 */
class RequestContentTypeTest {
    @Test
    fun `the sweep actually reaches the handlers, and classifies them`() {
        // Every assertion below is "for each handler", which an empty list
        // satisfies. A partially stale or narrowed import is the quiet failure —
        // the classpath read is `build/classes`, not the source tree.
        assertThat(handlers()).isNotEmpty()
        assertThat(handlers().map(::nameOf))
            .contains("AuthController.me", "AuthController.logout", "ProblemErrorController.handle")

        // The classification is half the rule, so it is asserted rather than
        // trusted: a bug that read every handler as a GET would leave the
        // state-changing check passing over an empty list.
        assertThat(handlers().filter(::isStateChanging).map(::nameOf)).contains("AuthController.logout")
        assertThat(handlers().filter(::isStateChanging).map(::nameOf)).doesNotContain("AuthController.me")
    }

    @Test
    fun `every state-changing handler declares a consumes that actually binds`() {
        val ineffective = stateChangingHandlers().flatMap(::ineffectiveConsumesOf)

        assertThat(ineffective)
            .describedAs(
                "A POST/PUT/PATCH whose `consumes` does not bind matches ANY content type, including the " +
                    "CORS-safelisted ones that skip the preflight — and it does so even when the handler reads no " +
                    "body, which is how POST /auth/logout was reachable cross-site. Declare at least one positive " +
                    "expression, e.g. consumes = MediaType.APPLICATION_JSON_VALUE, and no negated one " +
                    "(notes/2026-08-13-decision-static-front-and-content-type-gate.md).",
            ).isEmpty()
    }

    @Test
    fun `no state-changing handler switches its own consumes off`() {
        // `@RequestBody(required = false)` is the one thing that lowers
        // `bodyRequired`, and a lowered `bodyRequired` makes a body-less request
        // match REGARDLESS of `consumes` — so this defect hides behind a
        // declaration that reads as correct. It is latent rather than present: no
        // handler has a `@RequestBody` yet, and the first one to want an optional
        // body is who this is written for.
        val disarmed =
            stateChangingHandlers().flatMap { handler ->
                handler.parameters
                    .mapNotNull { it.annotations.firstOrNull { annotation -> annotation.rawType.name == REQUEST_BODY } }
                    .filter { it.get("required").orElse(true) == false }
                    .map { "${nameOf(handler)} takes @RequestBody(required = false)" }
            }

        assertThat(disarmed)
            .describedAs(
                "@RequestBody(required = false) lowers `bodyRequired`, and ConsumesRequestCondition then MATCHES " +
                    "any request with no body before it compares a single media type — so the handler is reachable " +
                    "body-less, unpreflighted, with the session cookie attached, however correct its `consumes` " +
                    "looks. If the body is genuinely optional, keep it required and let the client send `{}` " +
                    "(notes/2026-08-13-decision-static-front-and-content-type-gate.md).",
            ).isEmpty()
    }

    private fun stateChangingHandlers(): List<JavaMethod> = handlers().filter(::isStateChanging).filter { nameOf(it) !in EXEMPT }

    /** Why this handler's `consumes` fails to constrain anything, or nothing. */
    private fun ineffectiveConsumesOf(handler: JavaMethod): List<String> {
        val declared = consumesOf(handler)
        val negated = declared.filter { it.trim().startsWith("!") }
        if (negated.isNotEmpty()) {
            return negated.map {
                "${nameOf(handler)} declares consumes=\"$it\", and a negated expression MATCHES every type it does " +
                    "not exclude — it widens the condition instead of narrowing it"
            }
        }
        return if (declared.isEmpty()) listOf("${nameOf(handler)} declares no consumes") else emptyList()
    }

    @Test
    fun `no handler accepts a content type that skips the CORS preflight`() {
        val violations = handlers().flatMap(::violationsOf)

        assertThat(violations)
            .describedAs(
                "A content type a browser can send without a preflight leaves the request unprotected, and the " +
                    "preflight is v1's whole CSRF gate " +
                    "(notes/2026-08-13-decision-static-front-and-content-type-gate.md). This is a decision, not a " +
                    "lint: take the body as JSON, or under a type of our own — anything but these.",
            ).isEmpty()
    }

    private fun violationsOf(handler: JavaMethod): List<String> =
        consumesOf(handler).mapNotNull { expression ->
            preflightFreeReason(expression)?.let { reason ->
                "${nameOf(handler)} declares consumes=\"$expression\", which $reason"
            }
        } + multipartParametersOf(handler)

    /**
     * Every `consumes` that reaches this handler: its own mapping annotation and the
     * class-level one it inherits. Read off whatever annotation carries
     * `@RequestMapping`, so a composed annotation of our own would be covered the day
     * somebody writes one.
     */
    private fun consumesOf(handler: JavaMethod): List<String> = handler.mappings().flatMap { it.consumes() }

    private fun isStateChanging(handler: JavaMethod): Boolean =
        handler.mappings().any { annotation -> httpMethodsOf(annotation).any { it in STATE_CHANGING } }

    private fun JavaMethod.mappings(): List<JavaAnnotation<*>> = (annotations + owner.annotations).filter { it.rawType.isMapping() }

    private fun JavaClass.isMapping(): Boolean =
        name == RequestMapping::class.java.name ||
            isAnnotatedWith(RequestMapping::class.java) ||
            isMetaAnnotatedWith(RequestMapping::class.java)

    /**
     * `@PostMapping` says POST in its own name; `@RequestMapping` says it in a
     * property, and **says every method at once when that property is empty** — which
     * is why a bare `@RequestMapping` counts as state-changing here. The one place
     * that is wrong is [EXEMPT].
     */
    private fun httpMethodsOf(annotation: JavaAnnotation<*>): Set<String> {
        val simpleName = annotation.rawType.simpleName
        if (simpleName != "RequestMapping") return setOf(simpleName.removeSuffix("Mapping").uppercase())
        val declared =
            (annotation.get("method").orElse(null) as? Array<*>)
                ?.filterIsInstance<JavaEnumConstant>()
                ?.map { it.name() }
                .orEmpty()
        return if (declared.isEmpty()) ALL_METHODS else declared.toSet()
    }

    private fun JavaAnnotation<*>.consumes(): List<String> =
        when (val value = get("consumes").orElse(null)) {
            is Array<*> -> value.filterIsInstance<String>()
            is String -> listOf(value)
            else -> emptyList()
        }

    /**
     * The path that never names a media type. `@RequestPart` or a `MultipartFile`
     * parameter makes Spring resolve the request as multipart on its own.
     */
    private fun multipartParametersOf(handler: JavaMethod): List<String> =
        handler.parameters.mapNotNull { parameter ->
            val declared = parameter.type.name
            val reason =
                when {
                    parameter.annotations.any { it.rawType.name == REQUEST_PART } -> "@RequestPart"
                    declared.startsWith(MULTIPART_PACKAGE) || declared.startsWith("[L$MULTIPART_PACKAGE") -> declared
                    else -> return@mapNotNull null
                }
            "${nameOf(handler)} takes $reason, which accepts multipart/form-data without naming it in consumes"
        }

    /**
     * Why this expression is reachable without a preflight, or `null`.
     *
     * A leading `!` is skipped **only because a negation is refused outright** by
     * `every state-changing handler declares a consumes that actually binds` — it is
     * not safe, and the class comment says why. Parameters are stripped, because
     * `text/plain;charset=UTF-8` is `text/plain`.
     */
    private fun preflightFreeReason(expression: String): String? {
        val declared = expression.trim()
        if (declared.startsWith("!")) return null
        val type = declared.substringBefore(";").trim().lowercase()

        SAFELISTED.firstOrNull { type == it }?.let { return "accepts the CORS-safelisted type $it" }
        if (type == ABSENT_HEADER_DEFAULT) {
            return "accepts $ABSENT_HEADER_DEFAULT, which is how a request sending NO Content-Type header is matched " +
                "— and such a request is CORS-simple, so it never preflights"
        }
        if (type.endsWith("/*")) return "is a wildcard, so it subsumes types that skip the preflight"
        return null
    }

    private fun nameOf(handler: JavaMethod): String = "${handler.owner.simpleName}.${handler.name}"

    private fun handlers(): List<JavaMethod> =
        classes
            .flatMap { it.methods }
            .filter { method -> method.annotations.any { it.rawType.isMapping() } }

    private companion object {
        /**
         * The three content types a browser may send cross-site with no preflight
         * (Fetch, "CORS-safelisted request-header" / `Content-Type`).
         */
        val SAFELISTED = listOf("multipart/form-data", "application/x-www-form-urlencoded", "text/plain")

        /** What `ConsumesRequestCondition` matches a request with no `Content-Type` as. */
        const val ABSENT_HEADER_DEFAULT = "application/octet-stream"

        val STATE_CHANGING = setOf("POST", "PUT", "PATCH")
        val ALL_METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE")

        /**
         * The error dispatch, and an allowlist of one so a second entry has to be
         * argued for. `/error` is a bare `@RequestMapping` and so maps POST, but it
         * changes nothing, and it has to be able to render a problem document for a
         * request of *any* content type — the container re-dispatches carrying
         * whatever the original request had.
         */
        val EXEMPT = setOf("ProblemErrorController.handle")

        const val MULTIPART_PACKAGE = "org.springframework.web.multipart."
        const val REQUEST_PART = "org.springframework.web.bind.annotation.RequestPart"
        const val REQUEST_BODY = "org.springframework.web.bind.annotation.RequestBody"

        val classes: JavaClasses =
            ClassFileImporter()
                .withImportOption(ImportOption.DoNotIncludeTests())
                .importPackages("com.donghaeng")
    }
}
