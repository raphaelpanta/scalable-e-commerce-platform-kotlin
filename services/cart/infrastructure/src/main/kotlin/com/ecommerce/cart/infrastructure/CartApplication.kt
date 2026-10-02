package com.ecommerce.cart.infrastructure

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/** Service for the bounded context "cart": health, metrics and logs, no business logic yet. */
@SpringBootApplication
class CartApplication

fun main(args: Array<String>) {
    @Suppress("SpreadOperator") // the start-up arguments are copied once, at boot
    runApplication<CartApplication>(*args)
}
