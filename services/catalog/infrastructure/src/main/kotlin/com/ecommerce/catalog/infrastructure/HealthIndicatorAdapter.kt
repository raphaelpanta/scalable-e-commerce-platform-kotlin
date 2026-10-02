package com.ecommerce.catalog.infrastructure

import com.ecommerce.catalog.application.CheckServiceHealth
import com.ecommerce.catalog.domain.HealthStatus
import kotlinx.coroutines.reactor.mono
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.ReactiveHealthIndicator
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

/** Inbound adapter: exposes the [CheckServiceHealth] use case as an Actuator health contributor. */
@Component
class HealthIndicatorAdapter(
    private val checkServiceHealth: CheckServiceHealth,
) : ReactiveHealthIndicator {
    override fun health(): Mono<Health> =
        mono {
            when (val status = checkServiceHealth().status) {
                HealthStatus.Up -> Health.up().build()
                is HealthStatus.Down -> Health.down().withDetail("reason", status.reason).build()
            }
        }
}
