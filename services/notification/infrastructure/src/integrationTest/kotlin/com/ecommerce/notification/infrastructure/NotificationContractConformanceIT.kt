package com.ecommerce.notification.infrastructure

import com.ecommerce.conformance.OpenApiContract
import com.ecommerce.platform.messaging.testing.RecordedEventsConfig
import com.ecommerce.platform.testing.JwtFixture
import com.ecommerce.platform.testing.KafkaTestConfig
import com.ecommerce.platform.testing.PostgresTestConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val TIMEOUT: Duration = Duration.ofSeconds(10)
private const val OK = 200
private const val ACCEPTED = 202
private const val BAD_REQUEST = 400
private const val UNAUTHORIZED = 401
private const val FORBIDDEN = 403
private const val NOT_FOUND = 404
private const val CONFLICT = 409
private const val NOTIFICATIONS = "/api/v1/notifications"

/**
 * Every public operation of `contracts/openapi/notification.yaml`, with its success and documented error statuses,
 * exercised against the running service and validated request by request (pact-matrix rule 3, constitution
 * Principle V). The notifications are seeded in the database (one sent, one failed) so the reads and the retry are
 * deterministic. Statuses the service cannot produce on its own are deferred with the reason.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = ["management.server.port="])
@Import(PostgresTestConfig::class, KafkaTestConfig::class, RecordedEventsConfig::class, DeliveryTestConfig::class)
class NotificationContractConformanceIT(
    @LocalServerPort port: Int,
    @Autowired private val jwt: JwtFixture,
    @Autowired private val database: DatabaseClient,
) {
    private val contract = OpenApiContract.of("notification")
    private val client: WebTestClient =
        WebTestClient
            .bindToServer()
            .baseUrl("http://localhost:$port")
            .responseTimeout(TIMEOUT)
            .build()

    @Test
    fun `the public operations conform to notification yaml`() {
        val shopper = UUID.randomUUID()
        val sent = seed(shopper, "sent")
        val failed = seed(shopper, "failed")
        val shopperToken = jwt.tokenFor(shopper, setOf("shopper"))
        val operatorToken = jwt.tokenFor(UUID.randomUUID(), setOf("operator"))

        contract.check(get("$NOTIFICATIONS?channel=email&type=order_shipped&size=5", shopperToken), OK)
        contract.check(get("$NOTIFICATIONS?channel=fax", shopperToken), BAD_REQUEST)
        contract.check(get(NOTIFICATIONS, null), UNAUTHORIZED)
        contract.check(get(NOTIFICATIONS, operatorToken), FORBIDDEN)

        val failures = "$NOTIFICATIONS/failed"
        val filters = "accountId=$shopper&channel=email&failedFrom=2020-01-01T00:00:00Z"
        contract.check(get("$failures?$filters", operatorToken), OK)
        contract.check(get("$failures?failedFrom=yesterday", operatorToken), BAD_REQUEST)
        contract.check(get(failures, null), UNAUTHORIZED)
        contract.check(get(failures, shopperToken), FORBIDDEN)

        contract.check(post("$NOTIFICATIONS/not-a-uuid/retry", operatorToken), BAD_REQUEST)
        contract.check(post("$NOTIFICATIONS/$failed/retry", null), UNAUTHORIZED)
        contract.check(post("$NOTIFICATIONS/$failed/retry", shopperToken), FORBIDDEN)
        contract.check(post("$NOTIFICATIONS/${UUID.randomUUID()}/retry", operatorToken), NOT_FOUND)
        contract.check(post("$NOTIFICATIONS/$sent/retry", operatorToken), CONFLICT)
        contract.check(post("$NOTIFICATIONS/$failed/retry", operatorToken), ACCEPTED)

        contract.verify(DEFERRED)
    }

    /** A notification of [accountId] in [status], as the delivery scheduler leaves it. */
    private fun seed(
        accountId: UUID,
        status: String,
    ): UUID {
        val id = UUID.randomUUID()
        val at = Instant.now().minusSeconds(AGE_SECONDS)
        database
            .sql(
                "INSERT INTO notifications (id, source_event_id, kind, channel, account_id, recipient_address, " +
                    "subject, body, order_id, correlation_id, status, attempts, last_attempt_at, " +
                    "last_error_category, last_error, created_at, sent_at, failed_at) VALUES (:id, :event, " +
                    "'order_shipped', 'email', :account, :address, 'Order shipped', 'Your order has shipped.', " +
                    ":order, 'conformance', :status, :attempts, :at, :category, :error, :at, :sentAt, :failedAt)",
            ).bind("id", id)
            .bind("event", UUID.randomUUID())
            .bind("account", accountId)
            .bind("address", "shopper-$accountId@example.test")
            .bind("order", UUID.randomUUID())
            .bind("status", status)
            .bind("attempts", if (status == "failed") MAX_ATTEMPTS else 1)
            .bind("at", at)
            .bindNullable("category", "CHANNEL_UNAVAILABLE".takeIf { status == "failed" })
            .bindNullable("error", "SMTP server refused the message (451).".takeIf { status == "failed" })
            .bindNullable("sentAt", at.takeIf { status == "sent" })
            .bindNullable("failedAt", at.takeIf { status == "failed" })
            .fetch()
            .rowsUpdated()
            .block(TIMEOUT)
        return id
    }

    private inline fun <reified T : Any> DatabaseClient.GenericExecuteSpec.bindNullable(
        name: String,
        value: T?,
    ): DatabaseClient.GenericExecuteSpec = if (value == null) bindNull(name, T::class.java) else bind(name, value)

    private fun get(
        path: String,
        token: String?,
    ): WebTestClient.ResponseSpec =
        client
            .get()
            .uri(path)
            .headers { headers -> token?.let { headers.setBearerAuth(it) } }
            .exchange()

    private fun post(
        path: String,
        token: String?,
    ): WebTestClient.ResponseSpec =
        client
            .post()
            .uri(path)
            .headers { headers -> token?.let { headers.setBearerAuth(it) } }
            .exchange()

    private companion object {
        const val MAX_ATTEMPTS = 5
        const val AGE_SECONDS = 60L

        /** Documented statuses this layer cannot produce, with the reason. */
        val DEFERRED: Map<String, String> =
            mapOf(
                "* 429" to "rate limiting is the gateway's (contracts/gateway-routes.md), covered by its tests",
                "* 503" to "needs the notification database to be unavailable; outage behaviour is covered by the " +
                    "health tests and the Compose resilience suite",
            )
    }
}
