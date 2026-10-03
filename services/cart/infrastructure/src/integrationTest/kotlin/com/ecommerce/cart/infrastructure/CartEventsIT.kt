package com.ecommerce.cart.infrastructure

import com.ecommerce.platform.messaging.envelope.Envelope
import com.ecommerce.platform.messaging.envelope.EnvelopeJson
import com.ecommerce.platform.messaging.envelope.EventType
import com.ecommerce.platform.messaging.testing.EnvelopeFixtures
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.kafka.core.KafkaTemplate
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

private val EVENT_TIMEOUT: Duration = Duration.ofSeconds(30)
private val QUERY_TIMEOUT: Duration = Duration.ofSeconds(10)
private const val SEND_TIMEOUT_SECONDS = 10L
private const val UNIT_PRICE = 2450L
private const val BEANS_HELD = 3

/** The `OrderPaid` and `AccountDeleted` consumers (group `cart`), each effective once per `eventId`. */
class CartEventsIT(
    @Autowired private val kafka: KafkaTemplate<String, String>,
) : CartIntegrationTest() {
    private fun send(envelope: Envelope<*>) {
        kafka
            .send(EventType.valueOf(envelope.type).topic, envelope.aggregateId.toString(), EnvelopeJson.write(envelope))
            .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun quantities(account: UUID): Map<UUID, Int>? {
        val cartId =
            database
                .sql("SELECT id FROM cart WHERE account_id = :account")
                .bind("account", account)
                .map { row -> checkNotNull(row.get("id", UUID::class.java)) }
                .one()
                .block(QUERY_TIMEOUT) ?: return null
        return database
            .sql("SELECT product_id, quantity FROM cart_line WHERE cart_id = :id")
            .bind("id", cartId)
            .map { row ->
                checkNotNull(row.get("product_id", UUID::class.java)) to
                    checkNotNull(row.get("quantity", Int::class.javaObjectType))
            }.all()
            .collectList()
            .block(QUERY_TIMEOUT)
            .orEmpty()
            .toMap()
    }

    private fun addToAccount(
        account: UUID,
        productId: UUID,
        quantity: Int,
    ) {
        client
            .post()
            .uri("/api/v1/cart/lines")
            .header(HttpHeaders.AUTHORIZATION, bearer(account))
            .bodyValue(mapOf("productId" to productId, "quantity" to quantity))
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody(Void::class.java) // consume the body so the client releases it
    }

    private fun orderPaid(
        account: UUID,
        orderId: UUID,
        vararg lines: Pair<UUID, Int>,
        eventId: UUID = UUID.randomUUID(),
    ): Envelope<Map<String, Any>> =
        EnvelopeFixtures.envelope(
            EventType.OrderPaid,
            mapOf(
                "orderId" to orderId.toString(),
                "orderNumber" to "ORD-20261002-0001",
                "accountId" to account.toString(),
                "lines" to
                    lines.map { (productId, quantity) ->
                        mapOf(
                            "productId" to productId.toString(),
                            "name" to "Product",
                            "quantity" to quantity,
                            "unitPrice" to mapOf("amountMinor" to UNIT_PRICE, "currency" to "BRL"),
                        )
                    },
                "orderStatus" to "placed",
                "paymentStatus" to "approved",
            ),
            aggregateId = orderId,
            eventId = eventId,
        )

    @Test
    fun `an order paid takes the ordered quantities out of the account cart once per event`() {
        val account = UUID.randomUUID()
        val beans = catalog.product()
        val mug = catalog.product()
        addToAccount(account, beans, BEANS_HELD)
        addToAccount(account, mug, 1)
        val orderId = UUID.randomUUID()
        val paid = orderPaid(account, orderId, beans to 1, mug to 1)

        send(paid)
        send(paid)
        // Same key, same partition: once the marker's effect is visible, both deliveries of `paid` were handled.
        val marker = UUID.randomUUID()
        val markerProduct = catalog.product()
        addToAccount(marker, markerProduct, 1)
        send(orderPaid(marker, orderId, markerProduct to 1))
        // The marker's order bought its whole cart: the cart is deleted, not kept empty (data-model section 1).
        await().atMost(EVENT_TIMEOUT).until { quantities(marker) == null }

        quantities(account) shouldBe mapOf(beans to 2)
        val emptied =
            client
                .get()
                .uri("/api/v1/cart")
                .header(HttpHeaders.AUTHORIZATION, bearer(marker))
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(JSON_OBJECT)
                .returnResult()
                .responseBody
        (emptied?.get("lines") as List<*>).isEmpty() shouldBe true
    }

    @Test
    fun `a deleted account loses its cart`() {
        val account = UUID.randomUUID()
        addToAccount(account, catalog.product(), 1)

        send(
            EnvelopeFixtures.envelope(
                EventType.AccountDeleted,
                mapOf(
                    "accountId" to account.toString(),
                    "pseudonym" to "anon-4f9c2d71",
                    "deletedAt" to "2026-10-02T12:00:00Z",
                ),
                aggregateId = account,
            ),
        )

        await().atMost(EVENT_TIMEOUT).until { quantities(account) == null }
        quantities(account).shouldBeNull()
    }
}
