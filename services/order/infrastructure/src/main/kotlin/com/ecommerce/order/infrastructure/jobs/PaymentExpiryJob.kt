package com.ecommerce.order.infrastructure.jobs

import com.ecommerce.order.application.ExpirePendingPayments
import com.ecommerce.order.infrastructure.OrderProperties
import com.ecommerce.order.infrastructure.messaging.withCorrelationId
import com.ecommerce.platform.messaging.PeriodicJob
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.UUID

/**
 * The payment expiry job (FR-015): every `order.payment-expiry.interval` it cancels orders whose payment is still
 * pending 30 minutes after placement (`PAYMENT_EXPIRED`, `OrderCancelled`, stock released). It runs on the coroutine
 * loop of platform-messaging's [PeriodicJob] (no scheduler thread, nothing blocking); a full batch runs again at once.
 * Several instances are safe: each change is an optimistic update, so an order expires once.
 */
@Configuration(proxyBeanMethods = false)
class PaymentExpiryJob {
    @Bean
    fun paymentExpiryLoop(
        expirePendingPayments: ExpirePendingPayments,
        properties: OrderProperties,
    ): PeriodicJob {
        val settings = properties.paymentExpiry
        return PeriodicJob("payment-expiry", settings.interval) {
            withCorrelationId(UUID.randomUUID().toString()) { expirePendingPayments(settings.batchSize) } >=
                settings.batchSize
        }
    }
}
