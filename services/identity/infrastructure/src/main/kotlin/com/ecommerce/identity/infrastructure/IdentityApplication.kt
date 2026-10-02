package com.ecommerce.identity.infrastructure

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/** Service for the bounded context "identity": health, metrics and logs, no business logic yet. */
@SpringBootApplication
class IdentityApplication

fun main(args: Array<String>) {
    @Suppress("SpreadOperator") // the start-up arguments are copied once, at boot
    runApplication<IdentityApplication>(*args)
}
