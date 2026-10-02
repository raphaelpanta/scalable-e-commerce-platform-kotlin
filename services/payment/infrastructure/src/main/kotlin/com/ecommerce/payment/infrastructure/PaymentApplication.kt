package com.ecommerce.payment.infrastructure

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * The payment service (bounded context "payment", FR-013, FR-014, FR-016): charges and refunds through the
 * simulated provider for the order service (`/internal` endpoints), the public read API of attempts, refunds and
 * simulator rules, the outbox publication of the payment events and the consumers of `OrderPlaced` and
 * `OrderCancelled`.
 */
@SpringBootApplication
class PaymentApplication

fun main(args: Array<String>) {
    @Suppress("SpreadOperator") // the start-up arguments are copied once, at boot
    runApplication<PaymentApplication>(*args)
}
