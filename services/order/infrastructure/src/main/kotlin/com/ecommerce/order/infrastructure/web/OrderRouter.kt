package com.ecommerce.order.infrastructure.web

import com.ecommerce.platform.observability.observedCoRouter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse

/** The public routes of order.yaml under `/api/v1/orders` (coroutine handlers, no blocking). */
@Configuration(proxyBeanMethods = false)
class OrderRouter {
    @Bean
    fun orderRoutes(
        commands: OrderCommandHandlers,
        queries: OrderQueryHandlers,
    ): RouterFunction<ServerResponse> =
        observedCoRouter {
            "/api/v1/orders".nest {
                POST("", commands::placeOrder)
                GET("", queries::listOwnOrders)
                GET("/{orderId}", queries::getOwnOrder)
                POST("/{orderId}/cancellation", commands::cancelOwnOrder)
                POST("/{orderId}/status", commands::transitionOrderStatus)
            }
        }
}
