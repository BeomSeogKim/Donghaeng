package com.donghaeng

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.Architectures.layeredArchitecture
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import jakarta.persistence.Entity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Configuration
import org.springframework.security.web.authentication.AuthenticationFailureHandler
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.method.support.HandlerMethodArgumentResolver

/**
 * The package boundary and the layer direction, enforced.
 *
 * **`internal` does not do this job, and the rule file used to say it did.**
 * Kotlin's `internal` is *module*-scoped, `api/` is a single Gradle module, and
 * Kotlin has no package-private — so every type in `auth/` is visible to every
 * other package in the module no matter how it is marked. That is not a small
 * gap in an intended guarantee; there is no guarantee. A real compiler barrier
 * would be a Gradle module per domain, and building that for one module with one
 * domain is infrastructure by speculation
 * (notes/2026-08-12-decision-auth-package-structure.md). This test is the barrier
 * until that changes.
 *
 * **Most of these rules name no packages**, deliberately. A rule that enumerates
 * today's packages is one a new package silently escapes — the same failure `#80`
 * exists about — so the rules that must survive `#7`'s `wedding/` and `#11`'s
 * `guest/` derive the domain from the package name instead of listing it. The
 * layer rule goes one step further and names no packages at all, only annotations
 * and simple names. Only the declared chain inside `auth/` and the substrate list
 * name names, because each states a fact about those packages specifically.
 *
 * That property was verified rather than asserted: a throwaway
 * `com.donghaeng.wedding` reaching into `auth.account`'s repository was refused by
 * a rule written before the package existed, and a `@RestController` in the same
 * throwaway package was refused by the layer rule for the same reason.
 */
class ArchitectureTest {
    @Test
    fun `the importer found the application, so nothing below passes vacuously`() {
        // What this does and does not catch, stated correctly at the second
        // attempt — the first version claimed an empty import "satisfies every
        // rule perfectly", and that is false: ArchUnit 1.4.1 defaults
        // `failOnEmptyShould` to true, so a wholly empty import makes all five
        // rules fail loudly on their own.
        //
        // What it catches is the case that stays quiet: a PARTIALLY stale or
        // narrowed import. `failOnEmptyShould` sees classes and is satisfied,
        // while the classes that would have violated a rule are the ones missing.
        // The classpath read is `build/classes`, not the source tree.
        assertThat(classes).isNotEmpty()
        assertThat(classes.map { it.name })
            .contains(
                "com.donghaeng.auth.AuthController",
                "com.donghaeng.auth.account.LoginService",
                "com.donghaeng.auth.oauth.OAuthLoginSuccessHandler",
                "com.donghaeng.auth.session.SessionService",
                // The file facade, not the data class. The layer rule places the
                // entity-to-DTO mapping in the Service layer through this name, and
                // a compiler that stopped emitting it would leave the mapping
                // unplaced and the rule quietly wider.
                "com.donghaeng.auth.account.MeResponseKt",
            )
    }

    @Test
    fun `no package reaches into another domain's entities or repositories`() {
        // The standing rule (api/AGENTS.md, Architecture): cross-domain access goes
        // through a declared contract, never straight into another domain's rows.
        // It is written against the package NAME rather than a list, so `wedding/`
        // and `guest/` arrive already covered.
        classes()
            .should(notReachIntoAnotherDomainsPersistence())
            .check(classes)
    }

    @Test
    fun `auth's three clusters are one chain, and no arrow points back`() {
        // `oauth -> account -> session`, read off the code rather than chosen, and
        // each arrow is a sentence about what the packages hold:
        //
        //   * `oauth -> account`: the handshake hands over a ProviderProfile and
        //     gets nothing back. Nothing in `account/` names a provider TYPE — the
        //     provider is the varchar `ck_app_user_email_verifier_known` constrains
        //     — so #89's Kakao and Naver mappers add files to `oauth/` and change
        //     nothing here.
        //   * `account -> session`: a completed login must leave holding a session,
        //     so LoginService asks SessionService to issue one.
        //   * `session -> nothing`: `user_session.user_id` is a Long, not a
        //     mapping, so the session half knows neither an account nor a provider.
        //     That is what lets #5 grow `session/` while #89 grows `oauth/`.
        //
        // Derived from the order rather than written three times, so the day a
        // fourth cluster is inserted its two new prohibitions arrive with it.
        AUTH_CHAIN.forEachIndexed { index, upstream ->
            AUTH_CHAIN.drop(index + 1).forEach { downstream ->
                noClasses()
                    .that()
                    .resideInAPackage("$ROOT.auth.$downstream..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("$ROOT.auth.$upstream..")
                    .check(classes)
            }
        }
    }

    @Test
    fun `a layer is only reached from the layer above it`() {
        // NOTHING asserted layer direction before this rule. Every other rule in
        // this file is about packages, so a repository injecting a service, an
        // entity holding a controller and a service holding a controller all
        // compiled AND passed the whole suite — responsibility separation here was
        // a naming convention with nothing behind it. All three were run as
        // mutations against this rule and named in the record.
        //
        // Defined by ANNOTATION and name, never by package, so #7's `wedding/` and
        // #11's `guest/` are covered the day they are written and no list has to be
        // extended.
        //
        // `consideringAllDependencies` is the load-bearing option: a class in NO
        // layer may then not touch a layer at all. That is what makes the four
        // definitions below a decision rather than a default — a new kind of class
        // that reaches for a service or a row fails here until someone says which
        // layer it is, instead of quietly falling through.
        layeredArchitecture()
            .consideringAllDependencies()
            // The INBOUND EDGE, which is wider than `@RestController`. Spring
            // Security's filter chain and Spring MVC's argument resolution are also
            // places a request enters this application: OAuthLoginSuccessHandler
            // serves the OAuth callback and CurrentUserArgumentResolver decides who
            // the caller is. Treating them as anything else would mean either
            // exempting them by name or forbidding the calls they exist to make.
            // The three interfaces are FRAMEWORK types, so this list grows when
            // Spring gives us a new kind of entry point, not when we add a class.
            .layer(CONTROLLER)
            .definedBy(
                predicate("a request entry point") { candidate ->
                    candidate.isAnnotatedWith(RestController::class.java) ||
                        ENTRY_POINTS.any(candidate::isAssignableTo)
                },
            )
            // `@Service`, plus the entity-to-DTO mapping. api/AGENTS.md puts that
            // mapping in the response's own file as an extension function, where it
            // compiles to a `MeResponseKt`-style file facade — service-layer work
            // that happens to live in the DTO's file, and the one legitimate reader
            // of a row outside a service. Placed rather than exempted, so it gets a
            // service's permissions and a service's prohibitions both.
            .layer(SERVICE)
            .definedBy(
                predicate("a service, or the mapping in a response DTO's file") { candidate ->
                    candidate.isAnnotatedWith(Service::class.java) || candidate.simpleName.endsWith("ResponseKt")
                },
            )
            // The same heuristic the cross-domain rule uses, and it misses the same
            // things — `@MappedSuperclass`, `@Embeddable`, a repository named
            // otherwise. Shared deliberately: one definition of "a row" means a miss
            // is a miss in both rules rather than in whichever one nobody checked.
            .layer(PERSISTENCE)
            .definedBy(predicate("a row or the thing that loads it") { it.isPersistence() })
            // The composition root: it names the parts, which is its whole job. It
            // is a layer only because it has to be one — under
            // `consideringAllDependencies` an unplaced class may touch nothing, and
            // a filter chain that may not name the service it wires is not a filter
            // chain.
            .layer(WIRING)
            .definedBy(predicate("a configuration class") { it.isAnnotatedWith(Configuration::class.java) })
            .whereLayer(CONTROLLER)
            .mayOnlyBeAccessedByLayers(WIRING)
            .whereLayer(SERVICE)
            .mayOnlyBeAccessedByLayers(CONTROLLER, WIRING)
            .whereLayer(PERSISTENCE)
            .mayOnlyBeAccessedByLayers(SERVICE)
            // WIRING carries no `whereLayer` on purpose. `GoogleClientRegistration`
            // owns REGISTRATION_ID and the profile dispatch keys on it, so the edge
            // does read a constant off a configuration class. What must not happen —
            // an inner package depending on its domain's composition root — is
            // `a domain's inner packages do not depend on its composition root`,
            // which is about packages and stays there.
            .check(classes)
    }

    @Test
    fun `a domain's inner packages do not depend on its composition root`() {
        // `auth/` itself holds the wiring — the controller, the filter chain, the
        // MVC registration — and wiring is allowed to know about the parts. The
        // reverse would make the parts unusable without the wiring, and is how a
        // package graph acquires its first cycle. Derived, so it binds every
        // domain that ever gets inner packages.
        classes()
            .should(notDependOnItsOwnCompositionRoot())
            .check(classes)
    }

    @Test
    fun `the substrate does not depend on a domain`() {
        // `config/` and `error/` are underneath everything: the error contract, the
        // startup guards, the CORS policy, the frontend origin. A dependency
        // pointing from there into a domain would mean the substrate cannot be
        // reasoned about — or moved — without the domain.
        classes()
            .that()
            .resideInAnyPackage(*SUBSTRATE.map { "$ROOT.$it.." }.toTypedArray())
            .should(notDependOnADomain())
            .check(classes)
    }

    @Test
    fun `the package graph has no cycles, at either level`() {
        // Enumeration-free by construction, which is the property that makes this
        // outlive the packages that exist today.
        slices()
            .matching("$ROOT.(*)..")
            .should()
            .beFreeOfCycles()
            .check(classes)
        // `(*).(*)` DERIVES the inner pair. The first version named `auth` here,
        // under this same comment — which left #7's `wedding/planning` and
        // `wedding/venue` unexamined by the inner rule and lumped into one slice by
        // the outer one, so a mutual cycle between them passed both. Verified with
        // a throwaway pair of packages.
        slices()
            .matching("$ROOT.(*).(*)..")
            .should()
            .beFreeOfCycles()
            .check(classes)
    }

    /**
     * Phrased as `classes().should(NOT ...)` rather than
     * `noClasses().should(...)`, and that is not a style choice — it is the
     * difference between a check and a decoration.
     *
     * `noClasses().should(condition)` NEGATES the condition, so a custom condition
     * that adds `violated` events contributes nothing: the negation turns each one
     * into a satisfied event, the rule reports no violations, and the test passes
     * on code that breaks it. All three custom rules in this file were written
     * that way first and were **inert** — caught only because every mechanism in
     * this stop is verified by breaking it, and the deliberate violations were
     * being detected by the cycle rule instead.
     */
    private fun notReachIntoAnotherDomainsPersistence() =
        object : ArchCondition<JavaClass>("not reach into another domain's entities or repositories") {
            override fun check(
                item: JavaClass,
                events: ConditionEvents,
            ) {
                item.directDependenciesFromSelf
                    .filter { it.targetClass.isPersistence() }
                    // OUR domains only. Without this the rule fires on
                    // `AppUserRepository extends JpaRepository` and on Spring's own
                    // ClientRegistrationRepository — third-party types whose simple
                    // name ends in "Repository" and whose domain is `null`, so they
                    // differ from every domain there is.
                    .filter { domainOf(it.targetClass) != null }
                    .filter { domainOf(item) != domainOf(it.targetClass) }
                    .forEach { events.add(SimpleConditionEvent.violated(item, it.description)) }
            }
        }

    private fun notDependOnItsOwnCompositionRoot() =
        object : ArchCondition<JavaClass>("not depend on its own domain's root package") {
            override fun check(
                item: JavaClass,
                events: ConditionEvents,
            ) {
                val domain = domainOf(item) ?: return
                // Only classes that live BELOW the root, i.e. in an inner package.
                if (item.packageName == "$ROOT.$domain") return
                item.directDependenciesFromSelf
                    .filter { it.targetClass.packageName == "$ROOT.$domain" }
                    .forEach { events.add(SimpleConditionEvent.violated(item, it.description)) }
            }
        }

    private fun notDependOnADomain() =
        object : ArchCondition<JavaClass>("not depend on a domain package") {
            override fun check(
                item: JavaClass,
                events: ConditionEvents,
            ) {
                item.directDependenciesFromSelf
                    .filter { domainOf(it.targetClass) != null }
                    .forEach { events.add(SimpleConditionEvent.violated(item, it.description)) }
            }
        }

    /**
     * `null` for anything that is not in a domain — the substrate and the
     * application class itself. Everything else is named by its first package
     * segment, which is what makes these rules cover a package nobody has written
     * yet.
     */
    private fun domainOf(javaClass: JavaClass): String? {
        if (!javaClass.packageName.startsWith("$ROOT.")) return null
        val segment = javaClass.packageName.removePrefix("$ROOT.").substringBefore(".")
        return segment.takeIf { it.isNotEmpty() && it !in SUBSTRATE }
    }

    /**
     * A row or the thing that loads it. Recognised by annotation and by the naming
     * convention rather than by a list, for the same reason as everything else
     * here.
     */
    private fun JavaClass.isPersistence(): Boolean = isAnnotatedWith(Entity::class.java) || simpleName.endsWith("Repository")

    /**
     * `DescribedPredicate` has no SAM constructor from Kotlin — it is an abstract
     * class — and the description is not decoration: it is the whole of what a
     * layered-architecture failure prints about which layer a class was in.
     */
    private fun predicate(
        description: String,
        matches: (JavaClass) -> Boolean,
    ): DescribedPredicate<JavaClass> =
        object : DescribedPredicate<JavaClass>(description) {
            override fun test(candidate: JavaClass): Boolean = matches(candidate)
        }

    private companion object {
        const val ROOT = "com.donghaeng"

        const val CONTROLLER = "Controller"
        const val SERVICE = "Service"
        const val PERSISTENCE = "Persistence"
        const val WIRING = "Wiring"

        /**
         * Spring's own inbound edges — the places a request enters this application
         * without passing a `@RestController`.
         */
        val ENTRY_POINTS =
            listOf(
                AuthenticationSuccessHandler::class.java,
                AuthenticationFailureHandler::class.java,
                HandlerMethodArgumentResolver::class.java,
            )

        /** `auth/`'s inner packages, most dependent first. */
        val AUTH_CHAIN = listOf("oauth", "account", "session")

        /**
         * Imported once for the class, not once per test method: scanning the
         * classpath five times a run is what `archunit-junit5`'s `@AnalyzeClasses`
         * exists to avoid, and a companion buys the same thing without giving up
         * test names this repo can read.
         */
        val classes: JavaClasses =
            ClassFileImporter()
                .withImportOption(ImportOption.DoNotIncludeTests())
                .importPackages(ROOT)

        /**
         * Packages that are underneath every domain rather than beside them.
         *
         * `json/` joined them with `#173`: [com.donghaeng.json.Patch] is how every
         * partial-update body is read, so `wedding/` and `guest/` both point at it
         * and it may point at neither. Listing it here is what says so — the cycle
         * rule alone would allow it to reach into one domain, which is exactly how a
         * mechanism stops being one.
         */
        val SUBSTRATE = setOf("config", "error", "json")
    }
}
