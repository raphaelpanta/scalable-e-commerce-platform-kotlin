package com.ecommerce.order.infrastructure

import com.ecommerce.order.application.OrderRepository
import com.ecommerce.order.domain.Order
import com.ecommerce.platform.testing.JwtFixture
import kotlinx.coroutines.runBlocking
import org.awaitility.Awaitility.await
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

private val TIMEOUT: Duration = Duration.ofSeconds(10)
private val RACE_TIMEOUT: Duration = Duration.ofSeconds(20)
private val POLL: Duration = Duration.ofMillis(50)
private const val ORDERS = "/api/v1/orders"
private const val IDEMPOTENCY_KEY = "Idempotency-Key"

/**
 * Arranges the storefront provider states through the service's own repository and public API (blocking on purpose:
 * test code on the JUnit thread): empties the schema, seeds fixture orders, mints the bearer of the signed-in caller
 * and runs the checkouts whose answer the verifier's request replays (FR-008).
 */
class StorefrontHarness(
    private val database: DatabaseClient,
    private val orders: OrderRepository,
    private val jwt: JwtFixture,
    baseUrl: String,
) {
    private val client: WebClient = WebClient.create(baseUrl)

    /** Empties the service's tables: every provider state describes all the data its interaction needs. */
    fun reset() {
        database
            .sql("TRUNCATE status_changes, order_lines, orders, idempotency_records, order_number_sequences")
            .fetch()
            .rowsUpdated()
            .block(TIMEOUT)
    }

    /** Stores [fixtures] as they are. */
    fun store(vararg fixtures: Order) {
        runBlocking { fixtures.forEach { orders.insert(it) } }
    }

    /** The `Authorization` value of [caller]: a test token the service's JWKS validates. */
    fun bearerOf(caller: StorefrontCaller): String = "Bearer " + jwt.tokenFor(caller.accountId, listOf(caller.role))

    /** `POST /api/v1/orders` as ana with [key] and the storefront's checkout body; the status it answered. */
    fun checkout(
        key: String,
        token: String = StorefrontCart.APPROVE_TOKEN,
        revision: String = StorefrontCart.REVISION,
    ): Int = checkNotNull(placeOrder(key, token, revision).block(TIMEOUT))

    /**
     * A checkout of ana with [key] whose order is cancelled by her while its charge is still in flight (the charge
     * stub must answer slowly): the race of US4/AC2, answered 409 `order-cancelled` and stored for replays.
     */
    fun checkoutCancelledWhileCharging(key: String): Int {
        val checkout = placeOrder(key, StorefrontCart.APPROVE_TOKEN, StorefrontCart.REVISION).toFuture()
        await().atMost(RACE_TIMEOUT).pollInterval(POLL).until { pendingOrderOf(StorefrontCaller.ANA.accountId) != null }
        val orderId = checkNotNull(pendingOrderOf(StorefrontCaller.ANA.accountId))
        val cancelled =
            client
                .post()
                .uri("$ORDERS/$orderId/cancellation")
                .header(HttpHeaders.AUTHORIZATION, bearerOf(StorefrontCaller.ANA))
                .exchangeToMono { Mono.just(it.statusCode().value()) }
                .block(TIMEOUT)
        check(cancelled == OK) { "cancelling order $orderId during its charge answered $cancelled" }
        return checkNotNull(checkout.get(RACE_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
    }

    private fun placeOrder(
        key: String,
        token: String,
        revision: String,
    ): Mono<Int> =
        client
            .post()
            .uri(ORDERS)
            .header(HttpHeaders.AUTHORIZATION, bearerOf(StorefrontCaller.ANA))
            .header(IDEMPOTENCY_KEY, key)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                mapOf(
                    "addressId" to PactValues.ADDRESS_OWNED,
                    "cartRevision" to revision,
                    "paymentMethod" to mapOf("type" to "card", "token" to token),
                ),
            ).exchangeToMono { Mono.just(it.statusCode().value()) }

    private fun pendingOrderOf(account: UUID): UUID? =
        database
            .sql("SELECT id FROM orders WHERE account_id = :account AND payment_status = 'pending'")
            .bind("account", account)
            .map { row, _ -> checkNotNull(row.get("id", UUID::class.java)) }
            .all()
            .collectList()
            .block(TIMEOUT)
            ?.firstOrNull()

    private companion object {
        const val OK = 200
    }
}
