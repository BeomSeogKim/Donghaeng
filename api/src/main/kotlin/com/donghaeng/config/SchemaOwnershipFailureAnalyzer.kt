package com.donghaeng.config

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer
import org.springframework.boot.diagnostics.FailureAnalysis

/**
 * Turns the schema-ownership refusal into Boot's "APPLICATION FAILED TO START"
 * block, for the same reason as [MissingProfileFailureAnalyzer]: the operator
 * who is about to be told "no" has to be told what to type instead, or the
 * refusal reads as a bug in the app and gets worked around.
 *
 * Registered in META-INF/spring.factories — a FailureAnalyzer runs before the
 * context exists, so it cannot be a bean.
 *
 * The sentence starting "The schema of a real database is applied by hand" is a
 * CI CONTRACT: the `docker` job greps the packaged image's log for it (issue
 * #60). Reword it and update .github/workflows/ci.yml in the same change.
 *
 * That job also matches Boot's "APPLICATION FAILED TO START" banner, and the two
 * are not independent — the banner is printed only by
 * LoggingFailureAnalysisReporter, i.e. only when some FailureAnalyzer answered,
 * so both patterns prove THIS CLASS IS WIRED and neither proves the guard spoke.
 * The consequence to know before deleting this class: doing so is a defensible
 * simplification — the guard still refuses and the app still will not start —
 * but it turns that step red with "refused, but not as the guard", because Boot
 * would log a bare stack trace and no banner. This analyzer and that step change
 * together.
 */
internal class SchemaOwnershipFailureAnalyzer : AbstractFailureAnalyzer<SchemaOwnershipViolationException>() {
    override fun analyze(
        rootFailure: Throwable,
        cause: SchemaOwnershipViolationException,
    ): FailureAnalysis =
        FailureAnalysis(
            cause.violation +
                " The schema of a real database is applied by hand, from the migration files, by the person " +
                "who read them (notes/2026-08-09-decision-schema-ownership.md).",
            "Remove whatever set it — an environment variable or a command-line argument outranks every " +
                "application*.yml in the jar, so check those first — until the environment resolves\n" +
                "  ${SchemaOwnershipGuard.FLYWAY_ENABLED}=false\n" +
                "  ${SchemaOwnershipGuard.DDL_AUTO}=validate  (or none)\n" +
                "and leave ${SchemaOwnershipGuard.HBM2DDL_AUTO} unset.\n" +
                "If the schema really does need to change, apply the DDL yourself and restart.",
            cause,
        )
}
