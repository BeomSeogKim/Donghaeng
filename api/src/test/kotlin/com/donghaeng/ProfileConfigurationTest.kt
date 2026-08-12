package com.donghaeng

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.boot.env.PropertiesPropertySourceLoader
import org.springframework.boot.env.PropertySourceLoader
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.io.FileSystemResource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence

/**
 * Configuration has no other failure detector: nothing else breaks when a
 * profile file drifts. Two different kinds of check live here —
 *
 * - a structural sweep over every `application*` file on the resource tree,
 *   which is the one that has to hold for files that do not exist yet;
 * - assertions on named keys in the three files that exist today, which pin the
 *   specific decisions of issue #50.
 */
class ProfileConfigurationTest {
    private val resourceRoot = Path.of("src/main/resources")

    private fun configFiles(): List<Path> =
        Files
            .walk(resourceRoot)
            .asSequence()
            .filter { Files.isRegularFile(it) }
            .filter { it.fileName.toString().matches(Regex("""application.*\.(yml|yaml|properties)""")) }
            .sortedBy { it.toString() }
            .toList()

    /** Every document in the file — `.single()` would throw the day one uses `---`. */
    private fun load(path: Path): List<EnumerablePropertySource<*>> {
        val loader: PropertySourceLoader =
            if (path.toString().endsWith(".properties")) {
                PropertiesPropertySourceLoader()
            } else {
                YamlPropertySourceLoader()
            }
        return loader
            .load(path.toString(), FileSystemResource(path))
            .filterIsInstance<EnumerablePropertySource<*>>()
    }

    private fun properties(path: Path): Map<String, Any?> =
        load(path).flatMap { source -> source.propertyNames.map { it to source.getProperty(it) } }.toMap()

    private fun properties(fileName: String): Map<String, Any?> = properties(resourceRoot.resolve(fileName))

    private val base = properties("application.yml")
    private val dev = properties("application-dev.yml")
    private val prod = properties("application-prod.yml")

    // --- structural sweep -------------------------------------------------

    @Test
    fun `the sweep actually finds the configuration files`() {
        // Without this, a broken glob makes every sweep below pass vacuously.
        assertThat(configFiles().map { it.fileName.toString() })
            .contains("application.yml", "application-dev.yml", "application-prod.yml")
    }

    @Test
    fun `every value referencing the environment is a bare placeholder`() {
        // Asserted on values, not on key names: the leak this repo has a record
        // about hid inside `spring.datasource.url`, whose name says nothing.
        // A `${VAR:default}` segment is rejected too — that is where a literal
        // gets inlined while still looking like an environment reference.
        val bare = Regex("""^\$\{[A-Z0-9_]+}$""")
        configFiles().forEach { path ->
            properties(path).forEach { (name, value) ->
                val text = value?.toString() ?: return@forEach
                if (text.contains("\${")) {
                    assertThat(text)
                        .describedAs("%s · %s is an environment reference with no inline default", path, name)
                        .matches(bare.pattern)
                }
            }
        }
    }

    @Test
    fun `no value carries a credential inside a URI`() {
        // `postgresql://user:pw@host/db` — the documented shape
        // (notes/2026-08-08-decision-scaffold-secrets-and-surface.md).
        val credentialUri = Regex("""://[^/\s:]+:[^/\s@]+@""")
        configFiles().forEach { path ->
            properties(path).forEach { (name, value) ->
                assertThat(value?.toString() ?: "")
                    .describedAs("%s · %s embeds credentials in a URI", path, name)
                    .doesNotMatch { credentialUri.containsMatchIn(it) }
            }
        }
    }

    @Test
    fun `the CI workflows do not carry a credential inside a URI either`() {
        // The rule was mechanised over `application*.yml` and stopped there, so a
        // `psql 'postgresql://user:pw@host/db'` in a workflow walked straight past
        // it — which is exactly what the schema step of the `docker` job was
        // written as, sixty lines below the file's own argument against it. The
        // values there are throwaway, so what this defends is the SHAPE: a job log
        // publishes a connection string on any failure path, the same way
        // HikariCP does.
        //
        // Line-based rather than parsed: a workflow is a shell script wearing YAML,
        // and the credential would be inside a `run:` block that no property
        // binder ever looks at.
        val credentialUri = Regex("""://[^/\s:]+:[^/\s@]+@""")
        val workflows = Path.of("../.github/workflows")
        val files =
            Files
                .walk(workflows)
                .asSequence()
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".yml") }
                .toList()

        // Without this the sweep passes vacuously if the directory moves.
        assertThat(files).isNotEmpty()

        files.forEach { path ->
            Files.readAllLines(path).forEachIndexed { index, line ->
                assertThat(line)
                    .describedAs("%s:%d embeds credentials in a URI", path, index + 1)
                    .doesNotMatch { credentialUri.containsMatchIn(it) }
            }
        }
    }

    // --- the decisions of issue #50 --------------------------------------

    @Test
    fun `no environment logs SQL or its bound values`() {
        // In the base, so it holds for a mistyped profile too, and because
        // `show-sql: false` is a different code path from these three loggers.
        // Comparing to the string 'OFF' is what catches an unquoted OFF, which
        // YAML 1.1 parses as the boolean false.
        listOf(base, dev, prod).forEach { source ->
            assertThat(source["spring.jpa.show-sql"]).isNotEqualTo(true)
            assertThat(source["logging.level.org.hibernate.SQL"]).isIn(null, "OFF")
            assertThat(source["logging.level.org.hibernate.orm.jdbc.bind"]).isIn(null, "OFF")
            assertThat(source["logging.level.org.hibernate.orm.jdbc.extract"]).isIn(null, "OFF")
        }
        assertThat(base["spring.jpa.show-sql"]).isEqualTo(false)
        assertThat(base["logging.level.org.hibernate.SQL"]).isEqualTo("OFF")
        assertThat(base["logging.level.org.hibernate.orm.jdbc.bind"]).isEqualTo("OFF")
        assertThat(base["logging.level.org.hibernate.orm.jdbc.extract"]).isEqualTo("OFF")
    }

    @Test
    fun `no environment loosens what the base pins`() {
        // The base pins these three, but a profile file outranks the base, so
        // pinning alone stops nothing — the same reason the SQL-logging test
        // above sweeps rather than asserting on `base` only. Two of them are
        // the ones whose wrong value destroys data: `ddl-auto` on a profile
        // pointed at a real database, and `clean-disabled: false` in `dev`,
        // which points at the founder's shared local Postgres.
        //
        // This is also the guard on the cheapest way out of a red job: when a
        // future CI failure reads "schema does not match", `ddl-auto: update`
        // makes it green and takes the schema away from its owner.
        configFiles().forEach { path ->
            val source = properties(path)

            assertThat(source["spring.jpa.hibernate.ddl-auto"])
                .describedAs("%s · ddl-auto must not create or mutate the schema", path)
                .isIn(null, "none", "validate")

            // Both flags below are whitelists of safe values, not `isNotEqualTo`
            // of the unsafe one, and the quoting is why: YAML loads `true` as a
            // Boolean and `"true"` as the String "true", while Spring's relaxed
            // binding turns Flyway on for either. `isNotEqualTo(true)` therefore
            // passes on five quote characters. Same shape as `ddl-auto` above —
            // name what is allowed, so an unrecognised value is red by default.
            assertThat(source["spring.flyway.clean-disabled"])
                .describedAs("%s · flyway clean must stay disabled", path)
                .isIn(null, true, "true")

            // Flyway is a restrictive-base setting now, for the same reason as
            // the two above: a migration running unattended at startup is
            // irreversible work against a real database
            // (notes/2026-08-09-decision-schema-ownership.md). The suite opts in
            // through a system property set in build.gradle.kts, never here — so
            // no environment file may turn it on, not even dev.
            assertThat(source["spring.flyway.enabled"])
                .describedAs("%s · only the tests may run Flyway", path)
                .isIn(null, false, "false")

            // The three keys that decide how much of a server-side failure the
            // error page publishes. Boot's defaults are already the safe values,
            // which is exactly why they are pinned: a default is not a decision,
            // and `server.error.include-message: always` is a one-line change
            // nobody would flag in review. Whitelists again, for the quoting
            // reason above.
            assertThat(source["server.error.include-message"])
                .describedAs("%s · an error page never publishes an exception message", path)
                .isIn(null, "never")
            assertThat(source["server.error.include-stacktrace"])
                .describedAs("%s · an error page never publishes a stack trace", path)
                .isIn(null, "never")
            assertThat(source["server.error.include-exception"])
                .describedAs("%s · an error page never publishes an exception class name", path)
                .isIn(null, false, "false")

            // Same class as the three above, and the payload is what makes it
            // belong here: Tomcat's access log writes the request line, so the
            // OAuth callback's `code` and `state` — and any future token that
            // travels in a path — land in a file. Boot defaults it off; a default
            // is not a decision.
            assertThat(source["server.tomcat.accesslog.enabled"])
                .describedAs("%s · the access log would write the OAuth callback's code and state", path)
                .isIn(null, false, "false")

            // dev is the one profile that generates the OpenAPI document, and
            // it binds loopback to do it (asserted below).
            if (!path.fileName.toString().startsWith("application-dev.")) {
                assertThat(source["springdoc.api-docs.enabled"])
                    .describedAs("%s · no machine-readable introspection surface outside dev", path)
                    .isNotEqualTo(true)
            }
        }
    }

    @Test
    fun `every environment with a real database validates its mapping against it`() {
        // The counterpart to Flyway being off outside the tests: the schema the
        // suite builds from the migration files and the schema someone typed in
        // by hand are two things, and `validate` is the only thing that ever
        // compares them (notes/2026-08-09-decision-schema-ownership.md). The
        // base stays `none` — an environment that has declared nothing has
        // nothing to validate against.
        assertThat(dev["spring.jpa.hibernate.ddl-auto"]).isEqualTo("validate")
        assertThat(prod["spring.jpa.hibernate.ddl-auto"]).isEqualTo("validate")
    }

    @Test
    fun `the base never logs request details`() {
        // Cookie header, i.e. the session token.
        assertThat(base["spring.mvc.log-request-details"]).isEqualTo(false)
    }

    @Test
    fun `the base is the restrictive one and pins no profile of its own`() {
        assertThat(base["springdoc.api-docs.enabled"]).isEqualTo(false)
        assertThat(base["spring.jpa.hibernate.ddl-auto"]).isEqualTo("none")
        assertThat(base["spring.flyway.clean-disabled"]).isEqualTo(true)
        assertThat(base["spring.flyway.enabled"]).isEqualTo(false)
        assertThat(base["server.error.include-message"]).isEqualTo("never")
        assertThat(base["server.error.include-stacktrace"]).isEqualTo("never")
        assertThat(base["server.error.include-exception"]).isEqualTo(false)
        assertThat(base["spring.profiles.active"]).isNull()
        assertThat(base["spring.profiles.default"]).isNull()
    }

    @Test
    fun `only the profile files define the marker that makes a profile mandatory`() {
        assertThat(base["donghaeng.profile"]).isNull()
        assertThat(dev["donghaeng.profile"]).isEqualTo("dev")
        assertThat(prod["donghaeng.profile"]).isEqualTo("prod")
    }

    @Test
    fun `prod restates the flyway settings, trusts forwarded headers, publishes nothing`() {
        // Restated in the file rather than inherited, so a deploy review reads
        // them where it is already looking; pinned here so the restatement
        // cannot quietly rot away.
        assertThat(prod["spring.flyway.enabled"]).isEqualTo(false)
        assertThat(prod["spring.flyway.clean-disabled"]).isEqualTo(true)
        assertThat(prod["server.forward-headers-strategy"]).isEqualTo("native")
        assertThat(prod["springdoc.api-docs.enabled"]).isNotEqualTo(true)
    }

    // --- the session cookie and the OAuth credentials (#37) ---------------

    @Test
    fun `the session cookie flags are pinned in the base, and only dev loosens Secure`() {
        // `secure` is in the base because Boot defaults it to FALSE, so a profile
        // that had to remember to tighten it would invert the standing direction —
        // profiles loosen, the base is restrictive.
        assertThat(base["server.servlet.session.cookie.secure"]).isEqualTo(true)
        assertThat(base["server.servlet.session.cookie.http-only"]).isEqualTo(true)
        assertThat(base["server.servlet.session.cookie.path"]).isEqualTo("/")

        // `lax` and never `strict`: the OAuth callback is a top-level cross-site
        // navigation, so `strict` withholds the cookie at exactly the moment of
        // login (notes/2026-08-10-decision-auth-gate-and-sequence.md).
        assertThat(base["server.servlet.session.cookie.same-site"]).isEqualTo("lax")

        configFiles().forEach { path ->
            val source = properties(path)
            val isDev = path.fileName.toString().startsWith("application-dev.")

            if (!isDev) {
                assertThat(source["server.servlet.session.cookie.secure"])
                    .describedAs("%s · only dev, which serves http://localhost, may drop Secure", path)
                    .isIn(null, true, "true")
            }
            assertThat(source["server.servlet.session.cookie.http-only"])
                .describedAs("%s · the session token is never readable by script", path)
                .isIn(null, true, "true")
            assertThat(source["server.servlet.session.cookie.same-site"])
                .describedAs("%s · strict drops the cookie on the OAuth callback", path)
                .isIn(null, "lax")
        }
        assertThat(dev["server.servlet.session.cookie.secure"]).isEqualTo(false)
    }

    @Test
    fun `the session lifetimes are stated, and idle alone is never mistaken for both`() {
        assertThat(base["donghaeng.session.idle"]).isEqualTo("14d")
        assertThat(base["donghaeng.session.absolute"]).isEqualTo("90d")

        // `server.servlet.session.timeout` configures the container's JSESSIONID,
        // NOT the session above, and it expresses only an idle window. Setting it
        // would read as this decision while binding a different cookie, so no file
        // states it today.
        //
        // Read the ban narrowly. It says nothing about whether JSESSIONID should
        // have a timeout of its own — and there is now a case that it should, since
        // that cookie holds the OAuth authorization request (`state`, the PKCE
        // verifier, the nonce) for the length of a round trip and then nothing.
        // That question is #98's, and the day it is answered this assertion is what
        // has to move.
        configFiles().forEach { path ->
            assertThat(properties(path)["server.servlet.session.timeout"])
                .describedAs("%s · OUR session's expiry is SessionService's; JSESSIONID's own is #98", path)
                .isNull()
        }
    }

    @Test
    fun `no configuration file carries an OAuth client registration or its credentials`() {
        // The credentials are read from the environment by
        // com.donghaeng.auth.GoogleClientRegistration, and the registration itself
        // is built there — so the whole namespace is absent rather than partly
        // absent. A `${GOOGLE_CLIENT_ID}` line would also stop the application
        // booting anywhere the variable is not set, which is every machine that has
        // never seen a Google client, CI included.
        configFiles().forEach { path ->
            properties(path).forEach { (name, value) ->
                assertThat(name)
                    .describedAs("%s · OAuth client configuration belongs in code and the environment", path)
                    .doesNotStartWith("spring.security.oauth2")
                assertThat("$name=${value ?: ""}")
                    .describedAs("%s · no file names an OAuth credential", path)
                    .doesNotContain("GOOGLE_CLIENT")
                    .doesNotContain("client-secret")
            }
        }
    }

    @Test
    fun `CORS denies by default, allows one exact origin in dev, and never a wildcard`() {
        // BOUND, not read as keys, and that is the whole point of this version
        // (notes/2026-08-12-decision-cors.md). The first one inspected
        // `allowed-origins[0]`, `[1]`, … — and Spring binds a List<String> from a
        // comma-delimited scalar just as happily, so `allowed-origins: "https://a,https://b"`
        // produces a single un-indexed key, skips the guard, and every assertion
        // below goes vacuous. Binding is the form that cannot be spelled twice.
        assertThat(corsOrigins("application.yml")).isEmpty()
        assertThat(corsOrigins("application-dev.yml")).containsExactly("http://localhost:3000")
        assertThat(corsOrigins("application-prod.yml")).isEmpty()

        configFiles().forEach { path ->
            corsOrigins(path).forEach { origin ->
                // `*` is illegal beside allowCredentials anyway, so a browser
                // would refuse it — it is named so nobody "fixes" that by reaching
                // for allowedOriginPatterns, which works and fails open.
                assertThat(origin)
                    .describedAs("%s · %s is a wildcard or pattern origin", path, origin)
                    .doesNotContain("*")
                assertThat(origin)
                    .describedAs("%s · %s is not an origin (scheme and host, no path)", path, origin)
                    .matches("https?://[^/]+")
            }
        }
    }

    private fun corsOrigins(fileName: String): List<String> = corsOrigins(resourceRoot.resolve(fileName))

    private fun corsOrigins(path: Path): List<String> =
        Binder(MapConfigurationPropertySource(properties(path).filterValues { it != null }))
            .bind("donghaeng.cors.allowed-origins", Bindable.listOf(String::class.java))
            .orElse(emptyList())

    @Test
    fun `only dev states where a completed login sends the browser`() {
        // Never a value taken from the request, and never a guess: prod has no
        // frontend origin yet, so it states none and a login there fails loudly
        // rather than redirecting a freshly-issued session to invented text.
        assertThat(dev["donghaeng.frontend.base-url"]).isEqualTo("http://localhost:3000")
        assertThat(base["donghaeng.frontend.base-url"]).isNull()
        assertThat(prod["donghaeng.frontend.base-url"]).isNull()
    }

    @Test
    fun `dev opens the API document only on loopback`() {
        assertThat(dev["springdoc.api-docs.enabled"]).isEqualTo(true)
        assertThat(dev["server.address"]).isEqualTo("127.0.0.1")
        assertThat(dev["logging.level.com.donghaeng"]).isEqualTo("DEBUG")
    }
}
