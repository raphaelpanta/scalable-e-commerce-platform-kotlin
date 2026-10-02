package com.ecommerce.cart.infrastructure

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration

private val TIMEOUT: Duration = Duration.ofSeconds(10)

@SpringBootTest(webEnvironment = RANDOM_PORT, properties = ["management.server.port="])
@Import(PostgresContainerConfig::class)
class ServiceBaselineIntegrationTest(
    @LocalServerPort private val port: Int,
    @Autowired private val database: DatabaseClient,
) {
    @Test
    fun healthIsUp() {
        WebTestClient
            .bindToServer()
            .baseUrl("http://localhost:$port")
            .build()
            .get()
            .uri("/actuator/health")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .json("""{"status":"UP"}""", JsonCompareMode.STRICT)
    }

    @Test
    fun baselineMigrationIsRecordedAsVersionOne() {
        val baseline =
            database
                .sql("SELECT version, success FROM flyway_schema_history WHERE version = '1'")
                .fetch()
                .one()
                .block(TIMEOUT)

        baseline shouldBe mapOf("version" to "1", "success" to true)
    }
}
