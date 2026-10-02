package com.ecommerce.catalog.infrastructure

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import reactor.core.publisher.Hooks

/** Reference service for the bounded context "catalogue": health, metrics and logs, no business logic yet. */
@SpringBootApplication
class CatalogApplication

fun main(args: Array<String>) {
    // Restores thread locals (the MDC correlation id) on every Reactor operator, see CorrelationIdWebFilter.
    Hooks.enableAutomaticContextPropagation()
    @Suppress("SpreadOperator") // the start-up arguments are copied once, at boot
    runApplication<CatalogApplication>(*args)
}
