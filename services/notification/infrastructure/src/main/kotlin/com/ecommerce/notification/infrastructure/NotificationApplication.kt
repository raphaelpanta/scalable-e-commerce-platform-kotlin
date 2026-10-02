package com.ecommerce.notification.infrastructure

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/** Service for the bounded context "notification": health, metrics and logs, no business logic yet. */
@SpringBootApplication
class NotificationApplication

fun main(args: Array<String>) {
    @Suppress("SpreadOperator") // the start-up arguments are copied once, at boot
    runApplication<NotificationApplication>(*args)
}
