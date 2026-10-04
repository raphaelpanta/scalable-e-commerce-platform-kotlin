package com.ecommerce.payment.infrastructure

import com.ecommerce.payment.domain.RetryPolicy
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * `payment.*`: the bounded retry of pending charges and the retention of the cancelled orders payment remembers.
 * [cancelledOrdersRetention] (`payment.cancelled-orders-retention`) is how long a cancelled order and its contact
 * snapshot are kept so that a late approval is still refunded and announced, the late-charge window
 * ([retentionOfCancelledOrders]) when unset; the purge runs every [cancelledOrdersPurgeInterval].
 */
@ConfigurationProperties("payment")
data class PaymentProperties(
    val retry: Retry = Retry(),
    val cancelledOrdersRetention: Duration? = null,
    val cancelledOrdersPurgeInterval: Duration = Duration.ofMinutes(DEFAULT_PURGE_INTERVAL_MINUTES),
) {
    /**
     * The retention of a remembered cancelled order: the configured one, or else the late-charge window, the order's
     * 30-minute payment window plus the time the retry job may still take ([Retry.delay] for each of the
     * [Retry.maxAttempts] attempts), 33 minutes with the defaults.
     */
    fun retentionOfCancelledOrders(): Duration =
        cancelledOrdersRetention
            ?: Duration
                .ofMinutes(ORDER_PAYMENT_WINDOW_MINUTES)
                .plus(retry.delay.multipliedBy(retry.maxAttempts.toLong()))

    /**
     * `payment.retry.*`: a pending attempt is retried once it is [delay] old, until the order has [maxAttempts]
     * attempts; the job looks for due attempts every [interval], [batchSize] at a time.
     */
    data class Retry(
        val delay: Duration = Duration.ofSeconds(DEFAULT_DELAY_SECONDS),
        val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        val interval: Duration = Duration.ofSeconds(DEFAULT_INTERVAL_SECONDS),
        val batchSize: Int = DEFAULT_BATCH_SIZE,
    ) {
        val policy: RetryPolicy get() = RetryPolicy(delay, maxAttempts)
    }

    private companion object {
        const val DEFAULT_DELAY_SECONDS = 60L
        const val DEFAULT_MAX_ATTEMPTS = 3
        const val DEFAULT_INTERVAL_SECONDS = 10L
        const val DEFAULT_BATCH_SIZE = 100
        const val DEFAULT_PURGE_INTERVAL_MINUTES = 5L

        /** The order service's default `order.payment-window`: a pending order expires this long after placement. */
        const val ORDER_PAYMENT_WINDOW_MINUTES = 30L
    }
}
