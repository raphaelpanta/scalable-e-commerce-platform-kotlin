package com.ecommerce.catalog.infrastructure

import com.ecommerce.platform.testing.ContainerControl
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.json.JsonCompareMode
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * Health is UP while the database answers and DOWN within five seconds when it does not; the readiness group
 * follows the database, the liveness group does not (FR-026, US8/AC5). The groups answer exactly `{"status":"..."}`;
 * the overall health also lists the group names.
 */
class HealthEndpointIntegrationTest(
    @Autowired private val postgres: PostgreSQLContainer,
) : CatalogIntegrationTest() {
    @AfterEach
    fun resumeStorage() {
        ContainerControl.unpauseIfPaused(postgres)
    }

    @Test
    fun healthIsUpWhenTheDatabaseAnswers() {
        client
            .get()
            .uri("/actuator/health")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .json(withGroups("UP"), JsonCompareMode.STRICT)
    }

    @Test
    fun readinessAndLivenessAreUpWhenTheDatabaseAnswers() {
        listOf(READINESS, LIVENESS).forEach { path ->
            client
                .get()
                .uri(path)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .json("""{"status":"UP"}""", JsonCompareMode.STRICT)
        }
    }

    @Test
    fun healthIsDownWithinFiveSecondsWhenTheDatabaseIsUnreachable() {
        ContainerControl.pause(postgres)

        mapOf("/actuator/health" to withGroups("DOWN"), READINESS to """{"status":"DOWN"}""").forEach { (path, body) ->
            client
                .get()
                .uri(path)
                .exchange()
                .expectStatus()
                .isEqualTo(SERVICE_UNAVAILABLE)
                .expectBody()
                .json(body, JsonCompareMode.STRICT)
        }
        client
            .get()
            .uri(LIVENESS)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .json("""{"status":"UP"}""", JsonCompareMode.STRICT)
    }

    private companion object {
        const val SERVICE_UNAVAILABLE = 503
        const val READINESS = "/actuator/health/readiness"
        const val LIVENESS = "/actuator/health/liveness"

        fun withGroups(status: String): String = """{"status":"$status","groups":["liveness","readiness"]}"""
    }
}
