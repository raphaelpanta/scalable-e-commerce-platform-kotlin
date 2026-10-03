package com.ecommerce.payment.infrastructure

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.test.json.JsonCompareMode

private const val UP = """{"status":"UP"}"""
private const val HEALTH_WITH_GROUPS = """{"status":"UP","groups":["liveness","readiness"]}"""

/** Health and migrations of the service, in the shared integration context. */
class ServiceBaselineIntegrationTest : PaymentIntegrationTest() {
    /**
     * The overall health lists the probe groups next to its status; the readiness and liveness groups answer exactly
     * `{"status":"UP"}` on the management port (FR-026, US8/AC5; the test runs management on the server port).
     */
    @Test
    fun healthReadinessAndLivenessAreUp() {
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
    fun migrationsAreApplied() {
        column("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank") shouldBe
            listOf("1", "1.1", "2", "3", "4", "5")
    }
}
