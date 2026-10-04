package com.ecommerce.payment.infrastructure.web

import com.ecommerce.platform.observability.observedCoRouter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse

/**
 * The HTTP surface (coroutine handlers, no blocking): payment.yaml under `/api/v1/payments` (public, through the
 * gateway) and payment-internal.yaml under `/internal` (order service only).
 */
@Configuration(proxyBeanMethods = false)
class PaymentRouter {
    @Bean
    fun paymentRoutes(
        queries: PaymentQueryHandlers,
        internal: InternalPaymentHandlers,
    ): RouterFunction<ServerResponse> =
        observedCoRouter {
            "/api/v1/payments".nest {
                GET("/attempts/{attemptId}", queries::getPaymentAttempt)
                GET("/attempts", queries::listPaymentAttemptsForOrder)
                GET("/refunds/{refundId}", queries::getRefund)
                GET("/refunds", queries::listRefundsForOrder)
                GET("/simulator/rules", queries::getSimulatorRules)
            }
            POST("/internal/charges", internal::createCharge)
            POST("/internal/refunds", internal::createRefund)
        }
}
