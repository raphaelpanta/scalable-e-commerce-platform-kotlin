package com.ecommerce.payment.infrastructure.jobs

import com.ecommerce.payment.application.RetryPendingCharges
import com.ecommerce.payment.infrastructure.PaymentProperties
import com.ecommerce.payment.infrastructure.messaging.withCorrelationId
import com.ecommerce.platform.messaging.PeriodicJob
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.UUID

/**
 * The retry job of pending charges (US4 scenario 7): every `payment.retry.interval` it retries the pending attempts
 * that are `payment.retry.delay` old and below `payment.retry.max-attempts`, publishing the outcome of each retry. It
 * runs on the coroutine loop of platform-messaging's [PeriodicJob] (no scheduler thread, nothing blocking); a full
 * batch runs again at once. Several instances are safe: an attempt is voided once, so it is retried once.
 */
@Configuration(proxyBeanMethods = false)
class PaymentRetryJob {
    @Bean
    fun paymentRetryLoop(
        retryPendingCharges: RetryPendingCharges,
        properties: PaymentProperties,
    ): PeriodicJob {
        val settings = properties.retry
        return PeriodicJob("payment-retry", settings.interval) {
            withCorrelationId(UUID.randomUUID().toString()) { retryPendingCharges(settings.batchSize) } >=
                settings.batchSize
        }
    }
}
