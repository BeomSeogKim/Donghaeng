package com.donghaeng

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import jakarta.persistence.Entity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The package boundary, enforced.
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
 * `guest/` derive the domain from the package name instead of listing it. Only the
 * declared edge inside `auth/` and the substrate list name names, because each
 * states a fact about those packages specifically.
 *
 * That property was verified rather than asserted: a throwaway
 * `com.donghaeng.wedding` reaching into `auth.login`'s repository was refused by a
 * rule written before the package existed.
 */
class ArchitectureTest {
    private val classes: JavaClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages(ROOT)

    @Test
    fun `the importer found the application, so nothing below passes vacuously`() {
        // Every rule here is a "should NOT" over the imported classes, which an
        // empty import satisfies perfectly. This is the guard on the whole file —
        // and the classpath it reads is `build/classes`, not the source tree, so
        // an import that silently found nothing would make the rest decorative.
        assertThat(classes).isNotEmpty()
        assertThat(classes.map { it.name })
            .contains(
                "com.donghaeng.auth.AuthController",
                "com.donghaeng.auth.login.LoginService",
                "com.donghaeng.auth.session.SessionService",
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
    fun `auth session does not depend on auth login`() {
        // The one declared edge, and the direction is the point: login needs a
        // session issued, so `login -> session`. Nothing in session knows an
        // account exists — `user_session.user_id` is a Long, not a mapping — and
        // that is what lets #5 grow the session side and #89 grow the login side
        // without either reaching into the other.
        noClasses()
            .that()
            .resideInAPackage("$ROOT.auth.session..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("$ROOT.auth.login..")
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
        slices()
            .matching("$ROOT.auth.(*)..")
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

    private companion object {
        const val ROOT = "com.donghaeng"

        /** Packages that are underneath every domain rather than beside them. */
        val SUBSTRATE = setOf("config", "error")
    }
}
