package com.donghaeng.config

import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.InitializingBean
import org.springframework.stereotype.Component
import javax.sql.DataSource

/**
 * Refuses to start an environment whose JDBC driver would put row values into
 * exception messages (notes/2026-08-17-decision-log-masking-mechanism.md).
 *
 * PostgreSQL reports the offending value in an error response's `DETAIL` field,
 * and pgjdbc copies `DETAIL` into the exception message unless
 * `logServerErrorDetail` is off. A 5xx tells the client nothing, so
 * [com.donghaeng.error.GlobalErrorHandler] logs the whole throwable — which is
 * how `Key (lower(email))=(…) already exists.` reached a log line during the `#93`
 * audit. `application.yml` turns the flag off for every profile; this is what
 * makes that true of the environment that actually runs.
 *
 * It reads the BOUND [HikariDataSource] rather than a property string, and that is
 * the point. `SPRING_DATASOURCE_HIKARI_DATA_SOURCE_PROPERTIES_LOGSERVERERRORDETAIL`
 * in a deploy platform outranks every yml, and the map key reaches the driver
 * verbatim — so a value that bound under a differently-cased key is a value pgjdbc
 * never reads, and only the bound object shows which key survived.
 *
 * ## Why every URL is checked as well
 *
 * `DATABASE_URL` is supplied by the environment too, and pgjdbc's `parseURL` layers
 * the URL's query parameters ON TOP of the properties Hikari hands it — so
 * `…?logServerErrorDetail=true` wins outright, with the yml still saying `false`
 * and the whole suite green. The check is on the string rather than on pgjdbc's own
 * parse because the driver is a `runtimeOnly` dependency here; the shapes it would
 * accept and this refuses differ only in cases pgjdbc ignores anyway, which fails
 * closed.
 *
 * A URL can also arrive inside the driver properties, and that is the same hole one
 * step further in. `SPRING_DATASOURCE_HIKARI_DATA_SOURCE_CLASS_NAME` switches Hikari
 * from a `jdbcUrl` to a `DataSource` built by SETTERS: [HikariDataSource.getJdbcUrl]
 * is then null, so a URL check that looked only there would pass vacuously, while
 * `data-source-properties.url=…?logServerErrorDetail=true` reaches
 * `BaseDataSource.setUrl` and re-enables detail — with a perfectly correct
 * `logServerErrorDetail=false` sitting beside it in the same map. So a stated
 * `dataSourceClassName` is refused outright: it is not a shape this application
 * uses, and admitting it would mean re-deriving the driver's configuration from a
 * bean we do not build.
 *
 * ## What it does not see
 *
 * The primary [DataSource] bean, and nothing else. A second pool built by hand, or
 * one Boot never binds, is outside this and would need its own check.
 *
 * No branch prints a URL. HikariCP's own failure path publishes the whole `jdbcUrl`,
 * and this guard exists to make logs say less rather than more.
 */
@Component
internal class ServerErrorDetailGuard(
    private val dataSource: DataSource,
) : InitializingBean {
    override fun afterPropertiesSet() {
        val hikari =
            dataSource as? HikariDataSource
                ?: error(
                    "The DataSource is a ${dataSource.javaClass.name}, so the driver properties this " +
                        "application ships cannot be read — and `$PROPERTY` is one of them.",
                )

        val stated =
            hikari.dataSourceProperties[PROPERTY]
                ?.toString()
                ?.trim()
                ?.lowercase()
        check(stated == "false") {
            "`spring.datasource.hikari.data-source-properties.$PROPERTY` resolved to ${describe(stated)}, and " +
                "PostgreSQL's error DETAIL quotes the row value that failed. It is set to `false` in " +
                "application.yml for every profile; an environment may not reverse it."
        }

        check(hikari.dataSourceClassName == null) {
            "`spring.datasource.hikari.data-source-class-name` is set, which makes Hikari build the driver's " +
                "DataSource by setters — and its `url` property then carries whatever `$PROPERTY` it likes, " +
                "past the check below. This application connects by JDBC URL."
        }

        // Every URL this pool could connect through, not only `jdbcUrl` — see the
        // class comment on the setter-built shape.
        val urls = listOf(hikari.jdbcUrl) + URL_KEYS.map { hikari.dataSourceProperties[it]?.toString() }
        check(urls.none { URL_PARAMETER.containsMatchIn(it.orEmpty()) }) {
            "A JDBC URL sets `$PROPERTY` to something other than `false`, which overrides the driver " +
                "property above — PostgreSQL's error DETAIL quotes the row value that failed. Remove the " +
                "parameter from DATABASE_URL."
        }
    }

    private fun describe(value: String?) = if (value == null) "nothing (unset)" else "`$value`"

    private companion object {
        /** pgjdbc's own spelling. Hikari passes the key through untouched, so case matters. */
        const val PROPERTY = "logServerErrorDetail"

        /**
         * Both spellings `BaseDataSource` accepts. `url` is the JavaBeans property and
         * `URL` its historical alias, and pgjdbc honours either — so checking one is
         * checking half.
         */
        val URL_KEYS = listOf("url", "URL")

        /**
         * A whitelist of the one safe value, like [SchemaOwnershipGuard]'s: the unsafe
         * set is not enumerable, so an unrecognised spelling is refused rather than
         * assumed harmless.
         */
        val URL_PARAMETER = Regex("""[?&]$PROPERTY=(?!false(&|$))""", RegexOption.IGNORE_CASE)
    }
}
