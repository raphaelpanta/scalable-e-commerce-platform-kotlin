package com.ecommerce.order.infrastructure

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.test.json.JsonCompareMode

/** Health and migrations of the service, in the shared integration context. */
class ServiceBaselineIntegrationTest : OrderIntegrationTest() {
    @Test
    fun healthIsUp() {
        client
            .get()
            .uri("/actuator/health")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .json("""{"status":"UP"}""", JsonCompareMode.STRICT)
    }

    @Test
    fun migrationsAreApplied() {
        column("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank") shouldBe
            listOf("1", "1.1", "2")
    }
}
