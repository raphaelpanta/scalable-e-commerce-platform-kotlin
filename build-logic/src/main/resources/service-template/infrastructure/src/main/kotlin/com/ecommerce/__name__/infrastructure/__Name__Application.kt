package com.ecommerce.__name__.infrastructure

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/** Service for the bounded context "__name__": health, metrics and logs, no business logic yet. */
@SpringBootApplication
class __Name__Application

fun main(args: Array<String>) {
    @Suppress("SpreadOperator") // the start-up arguments are copied once, at boot
    runApplication<__Name__Application>(*args)
}
