package com.ecommerce.gateway

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class GatewayApplication

fun main(args: Array<String>) {
    @Suppress("SpreadOperator") // the start-up arguments are copied once, at boot
    runApplication<GatewayApplication>(*args)
}
