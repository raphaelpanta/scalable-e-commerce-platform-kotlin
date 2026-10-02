package com.ecommerce.catalog.infrastructure

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Service for the bounded context "catalogue" (user stories 1 and 7, FR-001..FR-003, FR-012): anonymous browsing and
 * search, operator maintenance of products, categories and stock, the internal reservation and pricing API of order
 * and cart, the stock events and the `OrderPaid`/`OrderPaymentFailed`/`OrderCancelled` consumers.
 */
@SpringBootApplication
class CatalogApplication

fun main(args: Array<String>) {
    @Suppress("SpreadOperator") // the start-up arguments are copied once, at boot
    runApplication<CatalogApplication>(*args)
}
