package com.ecommerce.catalog.infrastructure

import com.ecommerce.catalog.application.CheckServiceHealth
import com.ecommerce.catalog.application.HealthProbe
import com.ecommerce.catalog.domain.ServiceName
import com.ecommerce.catalog.domain.ServiceNameResult
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Wires the framework-free use cases; every [HealthProbe] adapter in the context takes part. */
@Configuration(proxyBeanMethods = false)
class UseCaseConfiguration {
    @Bean
    fun checkServiceHealth(probes: List<HealthProbe>): CheckServiceHealth {
        val service =
            when (val result = ServiceName.of(SERVICE_NAME)) {
                is ServiceNameResult.Valid -> result.name
                is ServiceNameResult.Invalid -> error(result.reason)
            }
        return CheckServiceHealth(service, probes)
    }

    private companion object {
        const val SERVICE_NAME = "catalog"
    }
}
