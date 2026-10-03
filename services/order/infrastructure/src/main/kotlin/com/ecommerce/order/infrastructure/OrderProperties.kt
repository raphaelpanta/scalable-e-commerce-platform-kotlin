package com.ecommerce.order.infrastructure

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** `order.*`: base URLs of the four internal dependencies, the payment expiry job and the idempotency purge. */
@ConfigurationProperties("order")
data class OrderProperties(
    val clients: Clients = Clients(),
    val paymentExpiry: PaymentExpiry = PaymentExpiry(),
    val idempotencyPurge: IdempotencyPurge = IdempotencyPurge(),
) {
    /** `order.clients.*` (`CART_URL`, `CATALOG_URL`, `PAYMENT_URL`, `IDENTITY_URL`). */
    data class Clients(
        val cartUrl: String = DEFAULT_URL,
        val catalogUrl: String = DEFAULT_URL,
        val paymentUrl: String = DEFAULT_URL,
        val identityUrl: String = DEFAULT_URL,
    )

    /** `order.payment-expiry.*`: how often and how many pending payments the expiry job examines. */
    data class PaymentExpiry(
        val interval: Duration = Duration.ofMinutes(1),
        val batchSize: Int = DEFAULT_BATCH_SIZE,
    )

    /** `order.idempotency-purge.*`: how often expired checkout idempotency records are deleted. */
    data class IdempotencyPurge(
        val interval: Duration = Duration.ofHours(1),
    )

    private companion object {
        const val DEFAULT_URL = "http://localhost:8080"
        const val DEFAULT_BATCH_SIZE = 100
    }
}
