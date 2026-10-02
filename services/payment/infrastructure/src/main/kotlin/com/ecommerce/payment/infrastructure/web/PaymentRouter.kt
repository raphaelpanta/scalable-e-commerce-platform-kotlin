package com.ecommerce.payment.infrastructure.web

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.coRouter

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
        coRouter {
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
