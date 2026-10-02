package com.ecommerce.payment.infrastructure

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/** Service for the bounded context "payment": health, metrics and logs, no business logic yet. */
@SpringBootApplication
class PaymentApplication

fun main(args: Array<String>) {
    @Suppress("SpreadOperator") // the start-up arguments are copied once, at boot
    runApplication<PaymentApplication>(*args)
}
