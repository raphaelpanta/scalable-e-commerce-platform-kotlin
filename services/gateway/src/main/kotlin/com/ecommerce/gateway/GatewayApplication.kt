package com.ecommerce.gateway

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import reactor.core.publisher.Hooks

/** The single public entry point of the platform (contracts/gateway-routes.md). */
@SpringBootApplication
@ConfigurationPropertiesScan
class GatewayApplication

fun main(args: Array<String>) {
    Hooks.enableAutomaticContextPropagation()
    @Suppress("SpreadOperator") // the start-up arguments are copied once, at boot
    runApplication<GatewayApplication>(*args)
}
