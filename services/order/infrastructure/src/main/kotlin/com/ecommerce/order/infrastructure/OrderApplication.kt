package com.ecommerce.order.infrastructure

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/** Service for the bounded context "order": health, metrics and logs, no business logic yet. */
@SpringBootApplication
class OrderApplication

fun main(args: Array<String>) {
    @Suppress("SpreadOperator") // the start-up arguments are copied once, at boot
    runApplication<OrderApplication>(*args)
}
