package com.ecommerce.catalog.application

import com.ecommerce.catalog.domain.HealthStatus
import com.ecommerce.catalog.domain.ServiceName

/** The combined health of [service]. */
data class HealthReport(
    val service: ServiceName,
    val status: HealthStatus,
)
