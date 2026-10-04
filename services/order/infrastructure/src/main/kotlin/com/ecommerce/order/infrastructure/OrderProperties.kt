package com.ecommerce.order.infrastructure

import com.ecommerce.order.domain.Order
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * `order.*`: base URLs of the four internal dependencies, the payment window (`order.payment-window`, how long a
 * payment may stay pending before the order expires with `PAYMENT_EXPIRED`), the payment expiry job and the
 * idempotency purge.
 */
@ConfigurationProperties("order")
data class OrderProperties(
    val clients: Clients = Clients(),
    val paymentWindow: Duration = Order.DEFAULT_PAYMENT_WINDOW,
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

    /**
     * `order.payment-expiry.*`: how often and how many pending payments the expiry job examines; an order expires at
     * most [interval] after its payment window ended, so a short window needs a short interval.
     */
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
