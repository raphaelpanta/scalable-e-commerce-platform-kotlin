package com.ecommerce.catalog.application

import com.ecommerce.catalog.domain.HealthStatus

/** Outbound port: checks one dependency of the service (a database, a broker, ...). */
fun interface HealthProbe {
    suspend fun check(): HealthStatus
}
