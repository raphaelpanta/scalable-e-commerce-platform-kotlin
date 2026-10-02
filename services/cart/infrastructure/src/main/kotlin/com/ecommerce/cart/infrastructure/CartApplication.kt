package com.ecommerce.cart.infrastructure

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Service for the bounded context "cart" (user story 2, FR-008..FR-010): anonymous and account carts priced live
 * from the catalogue, the merge on sign-in, the internal cart API of order, and the `OrderPaid`/`AccountDeleted`
 * consumers.
 */
@SpringBootApplication
class CartApplication

fun main(args: Array<String>) {
    @Suppress("SpreadOperator") // the start-up arguments are copied once, at boot
    runApplication<CartApplication>(*args)
}
