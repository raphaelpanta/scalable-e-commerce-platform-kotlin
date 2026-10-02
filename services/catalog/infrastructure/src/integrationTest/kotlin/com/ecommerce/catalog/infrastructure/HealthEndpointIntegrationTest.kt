package com.ecommerce.catalog.infrastructure

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.reactive.server.WebTestClient
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Duration

@SpringBootTest(webEnvironment = RANDOM_PORT, properties = ["management.server.port="])
@Import(PostgresContainerConfig::class)
class HealthEndpointIntegrationTest(
    @LocalServerPort private val port: Int,
    @Autowired private val postgres: PostgreSQLContainer,
) {
    private val client: WebTestClient by lazy {
        WebTestClient
            .bindToServer()
            .baseUrl("http://localhost:$port")
            .responseTimeout(Duration.ofSeconds(5))
            .build()
    }

    @AfterEach
    fun resumeStorage() {
        if (postgres.currentContainerInfo.state.paused == true) {
            postgres.dockerClient.unpauseContainerCmd(postgres.containerId).exec()
        }
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
        postgres.dockerClient.pauseContainerCmd(postgres.containerId).exec()

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
