package com.ecommerce.order.infrastructure

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * The order service (bounded context "order", FR-011..FR-017): checkout with synchronous stock reservation and
 * idempotency, order history, cancellation and operator transitions, payment and account event consumers, and the
 * payment expiry job.
 */
@SpringBootApplication
class OrderApplication

fun main(args: Array<String>) {
    @Suppress("SpreadOperator") // the start-up arguments are copied once, at boot
    runApplication<OrderApplication>(*args)
}
