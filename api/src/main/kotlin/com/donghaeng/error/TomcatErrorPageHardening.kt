package com.donghaeng.error

import org.apache.catalina.valves.ErrorReportValve
import org.springframework.boot.web.embedded.tomcat.ConfigurableTomcatWebServerFactory
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.stereotype.Component

/**
 * The third producer of an error response, and the only one neither
 * [GlobalErrorHandler] nor [ProblemErrorController] can reach: Tomcat's own
 * `ErrorReportValve`.
 *
 * It answers when the failure happens outside anything Spring can see — a
 * request target the connector rejects while parsing, or a `Filter` registered
 * for the `ERROR` dispatch that throws while the container is already handling a
 * failure. `StandardHostValve` then falls through to this valve, which reads
 * `RequestDispatcher.ERROR_EXCEPTION` — still holding the ORIGINAL throwable —
 * and, at its own defaults, renders an HTML page carrying `Type Exception
 * Report`, the exception message, a partial stack trace and `Apache
 * Tomcat/10.1.55`. Measured, not assumed: that is the literal body observed on
 * the wire before this class existed.
 *
 * ## Why this exists when Boot already does it
 *
 * Boot does harden the valve — in `TomcatWebServerFactoryCustomizer`
 * (spring-boot-autoconfigure), not in `TomcatServletWebServerFactory` — but
 * **only when `server.error.include-stacktrace` resolves to `never`**. That is
 * the whole condition. So `SERVER_ERROR_INCLUDE_STACKTRACE=always` set in a
 * deploy platform does not merely widen an error page this application no
 * longer serves; it puts the stack trace back on the container's page, where
 * neither producer is looking. The environment outranks every yml
 * (notes/2026-08-09-decision-schema-ownership.md), so the pin in
 * `application.yml` cannot be the only thing holding this.
 *
 * Unconditional here on purpose: the hardening should not depend on a property
 * whose subject is something else.
 *
 * ## Why adding a valve rather than editing one
 *
 * At customiser time the Host pipeline may not hold an `ErrorReportValve` yet —
 * `StandardHost` adds its default at start, and skips doing so when one is
 * already present. Adding ours is therefore what prevents the permissive
 * default from ever being created. When Boot adds its own too, both are
 * hardened and which one reports is immaterial.
 */
@Component
internal class TomcatErrorPageHardening : WebServerFactoryCustomizer<ConfigurableTomcatWebServerFactory> {
    override fun customize(factory: ConfigurableTomcatWebServerFactory) {
        factory.addContextCustomizers({ context ->
            context.parent.pipeline.addValve(
                ErrorReportValve().apply {
                    isShowReport = false
                    isShowServerInfo = false
                },
            )
        })
    }
}
