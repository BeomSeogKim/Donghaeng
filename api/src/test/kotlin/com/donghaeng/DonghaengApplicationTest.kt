package com.donghaeng

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import javax.sql.DataSource

// The suite is not an environment: it has no profile of its own, because
// @ServiceConnection supplies the datasource and outranks spring.datasource.*.
// It still has to satisfy the profile marker, so it states one explicitly.
@SpringBootTest(properties = ["donghaeng.profile=test"])
@Testcontainers
class DonghaengApplicationTest {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired
    lateinit var dataSource: DataSource

    @Test
    fun `context loads against a real postgres`() {
        dataSource.connection.use { connection ->
            assertThat(connection.metaData.databaseProductName).isEqualTo("PostgreSQL")
        }
    }
}
