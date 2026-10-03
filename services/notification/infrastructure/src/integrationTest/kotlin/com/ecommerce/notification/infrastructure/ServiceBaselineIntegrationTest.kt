package com.ecommerce.notification.infrastructure

import com.ecommerce.platform.messaging.testing.RecordedEventsConfig
import com.ecommerce.platform.testing.KafkaTestConfig
import com.ecommerce.platform.testing.PostgresTestConfig
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

private const val UP = """{"status":"UP"}"""
private const val HEALTH_WITH_GROUPS = """{"status":"UP","groups":["liveness","readiness"]}"""

private val TIMEOUT: Duration = Duration.ofSeconds(10)

@SpringBootTest(webEnvironment = RANDOM_PORT, properties = ["management.server.port="])
@Import(PostgresTestConfig::class, KafkaTestConfig::class, RecordedEventsConfig::class, DeliveryTestConfig::class)
class ServiceBaselineIntegrationTest(
    @LocalServerPort private val port: Int,
    @Autowired private val database: DatabaseClient,
) {
    /**
     * The overall health lists the probe groups next to its status; the readiness and liveness groups answer exactly
     * `{"status":"UP"}` on the management port (FR-026, US8/AC5; the test runs management on the server port).
     */
    @Test
    fun healthReadinessAndLivenessAreUp() {
        val client =
            WebTestClient
                .bindToServer()
                .baseUrl("http://localhost:$port")
                .build()
        mapOf(
            "/actuator/health" to HEALTH_WITH_GROUPS,
            "/actuator/health/readiness" to UP,
            "/actuator/health/liveness" to UP,
        ).forEach { (path, body) ->
            client
                .get()
                .uri(path)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .json(body, JsonCompareMode.STRICT)
        }
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
