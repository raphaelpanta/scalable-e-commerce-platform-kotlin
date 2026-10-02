package com.ecommerce.cart.infrastructure.web

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.coRouter

/** The HTTP surface: cart.yaml (public, through the gateway) and cart-internal.yaml (order only). */
@Configuration(proxyBeanMethods = false)
class CartRoutes {
    @Bean
    fun cartRouter(
        cart: CartHandlers,
        internal: InternalCartHandlers,
    ): RouterFunction<ServerResponse> =
        coRouter {
            GET("/api/v1/cart", cart::get)
            DELETE("/api/v1/cart", cart::clear)
            POST("/api/v1/cart/lines", cart::addLine)
            PUT("/api/v1/cart/lines/{lineId}", cart::updateLine)
            DELETE("/api/v1/cart/lines/{lineId}", cart::removeLine)
            POST("/api/v1/cart/merge", cart::merge)
            GET("/internal/carts/by-account/{accountId}", internal::get)
            POST("/internal/carts/by-account/{accountId}/clear", internal::clear)
        }
}
