package com.ecommerce.catalog.application

import com.ecommerce.catalog.domain.HealthStatus
import com.ecommerce.catalog.domain.ServiceName

/** Use case: asks every probe, even after one fails, and combines their statuses into one report. */
class CheckServiceHealth(
    private val service: ServiceName,
    private val probes: List<HealthProbe>,
) {
    suspend operator fun invoke(): HealthReport {
        val statuses = probes.map { it.check() }
        return HealthReport(service, HealthStatus.combine(statuses))
    }
}
