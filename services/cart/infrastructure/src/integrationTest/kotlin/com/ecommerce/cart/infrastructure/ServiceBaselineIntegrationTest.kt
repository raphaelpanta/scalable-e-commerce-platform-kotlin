package com.ecommerce.cart.infrastructure

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.test.json.JsonCompareMode
import java.time.Duration

private val TIMEOUT: Duration = Duration.ofSeconds(10)

class ServiceBaselineIntegrationTest : CartIntegrationTest() {
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
    fun migrationsAreRecordedInOrder() {
        val versions =
            database
                .sql("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
                .map { row -> checkNotNull(row.get("version", String::class.java)) }
                .all()
                .collectList()
                .block(TIMEOUT)

        versions shouldBe listOf("1", "1.1", "2")
    }
}
