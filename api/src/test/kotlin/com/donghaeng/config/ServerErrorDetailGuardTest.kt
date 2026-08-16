package com.donghaeng.config

import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * The guard's own behaviour, without a database. `ServerErrorDetailMaskingTest`
 * proves the value is gone from a real unique violation; this proves the guard
 * refuses the environments where it would come back — which is the half no green
 * suite would otherwise notice, because a deploy platform's environment variable
 * is invisible to every test that reads a committed file
 * (notes/2026-08-17-decision-log-masking-mechanism.md).
 *
 * A [HikariDataSource] built here connects to nothing: the pool initialises on the
 * first `getConnection()`, and the guard only reads configuration.
 */
class ServerErrorDetailGuardTest {
    private fun guardOver(
        url: String,
        vararg properties: Pair<String, Any>,
    ) = ServerErrorDetailGuard(
        HikariDataSource().apply {
            jdbcUrl = url
            properties.forEach { (name, value) -> addDataSourceProperty(name, value) }
        },
    )

    private fun refuses(
        url: String,
        vararg properties: Pair<String, Any>,
    ) = assertThatThrownBy { guardOver(url, *properties).afterPropertiesSet() }
        .isInstanceOf(IllegalStateException::class.java)

    private fun starts(
        url: String,
        vararg properties: Pair<String, Any>,
    ) = assertThatCode { guardOver(url, *properties).afterPropertiesSet() }.doesNotThrowAnyException()

    @Test
    fun `the committed arrangement starts`() {
        starts(URL, PROPERTY to "false")
    }

    @Test
    fun `a boolean false starts too, because that is what YAML binds`() {
        // `logServerErrorDetail: false` in application.yml binds as a Boolean, not
        // as the string. Hikari stringifies it on the way to the driver; a guard
        // that compared against `"false"` only would refuse the very file it exists
        // to protect.
        starts(URL, PROPERTY to false)
    }

    @Test
    fun `an environment that switches the driver's detail logging back on refuses`() {
        // SPRING_DATASOURCE_HIKARI_DATA_SOURCE_PROPERTIES_LOGSERVERERRORDETAIL=true,
        // typed once into a deploy platform, outranks the committed base file.
        refuses(URL, PROPERTY to "true")
    }

    @Test
    fun `an absent setting refuses, because pgjdbc defaults it ON`() {
        refuses(URL)
    }

    @Test
    fun `a key the driver would never read refuses rather than looking set`() {
        // Hikari passes these through verbatim, so the key is pgjdbc's and is
        // case-sensitive: `log-server-error-detail` binds happily, reads as
        // configured to a human, and leaves the driver at its default.
        refuses(URL, "log-server-error-detail" to "false")
        refuses(URL, "logservererrordetail" to "false")
    }

    @Test
    fun `a JDBC URL that re-enables it refuses, even with the property set correctly`() {
        // pgjdbc layers the URL's query parameters ON TOP of the properties Hikari
        // hands it, so this wins outright — and DATABASE_URL comes from the
        // environment just like the property above.
        refuses("$URL?$PROPERTY=true", PROPERTY to "false")
        refuses("$URL?ApplicationName=donghaeng&$PROPERTY=true", PROPERTY to "false")
        // Unrecognised spellings are refused rather than assumed harmless, the same
        // whitelist shape SchemaOwnershipGuard uses.
        refuses("$URL?$PROPERTY=", PROPERTY to "false")
        refuses("$URL?$PROPERTY=yes", PROPERTY to "false")
    }

    @Test
    fun `a JDBC URL that states it as false is left alone`() {
        starts("$URL?$PROPERTY=false", PROPERTY to "false")
        starts("$URL?$PROPERTY=false&ApplicationName=donghaeng", PROPERTY to "false")
    }

    @Test
    fun `a URL naming something else entirely is not mistaken for it`() {
        starts("$URL?ApplicationName=donghaeng", PROPERTY to "false")
    }

    @Test
    fun `a driver DataSource built by setters refuses, because its URL is not the jdbcUrl`() {
        // SPRING_DATASOURCE_HIKARI_DATA_SOURCE_CLASS_NAME=org.postgresql.ds.PGSimpleDataSource
        // switches Hikari to setter-based construction: `jdbcUrl` is then null, so
        // the URL check below would pass over an empty string while
        // `data-source-properties.url` carried the parameter into
        // BaseDataSource.setUrl — with a perfectly correct `false` beside it in the
        // same map. Refused outright rather than re-derived.
        assertThatThrownBy {
            ServerErrorDetailGuard(
                HikariDataSource().apply {
                    dataSourceClassName = "org.postgresql.ds.PGSimpleDataSource"
                    addDataSourceProperty("url", "$URL?$PROPERTY=true")
                    addDataSourceProperty(PROPERTY, "false")
                },
            ).afterPropertiesSet()
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `a URL smuggled through the driver properties refuses under either spelling`() {
        // The same hole with the class name absent: `BaseDataSource` honours both
        // `url` and its historical `URL` alias, so checking one is checking half.
        refuses(URL, PROPERTY to "false", "url" to "$URL?$PROPERTY=true")
        refuses(URL, PROPERTY to "false", "URL" to "$URL?$PROPERTY=true")
    }

    private companion object {
        const val PROPERTY = "logServerErrorDetail"

        /** Credential-free, like the real `DATABASE_URL` (api/AGENTS.md, Security posture). */
        const val URL = "jdbc:postgresql://localhost:5432/donghaeng"
    }
}
