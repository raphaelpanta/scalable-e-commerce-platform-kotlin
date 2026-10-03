package com.ecommerce.payment.infrastructure

import com.ecommerce.payment.domain.RetryPolicy
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** `payment.*`: the bounded retry of pending charges. */
@ConfigurationProperties("payment")
data class PaymentProperties(
    val retry: Retry = Retry(),
) {
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
    }
}
