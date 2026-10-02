package com.ecommerce.catalog.infrastructure

import com.ecommerce.platform.testing.ContainerControl
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.json.JsonCompareMode
import org.testcontainers.postgresql.PostgreSQLContainer

/** Health is UP while the database answers and DOWN within five seconds when it does not. */
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
            .json("""{"status":"UP"}""", JsonCompareMode.STRICT)
    }

    @Test
    fun healthIsDownWithinFiveSecondsWhenTheDatabaseIsUnreachable() {
        ContainerControl.pause(postgres)

        client
            .get()
            .uri("/actuator/health")
            .exchange()
            .expectStatus()
            .isEqualTo(SERVICE_UNAVAILABLE)
            .expectBody()
            .json("""{"status":"DOWN"}""", JsonCompareMode.STRICT)
    }

    private companion object {
        const val SERVICE_UNAVAILABLE = 503
    }
}
