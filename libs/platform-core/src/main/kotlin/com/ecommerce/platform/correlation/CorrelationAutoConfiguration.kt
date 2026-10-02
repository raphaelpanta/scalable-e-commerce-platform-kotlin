package com.ecommerce.platform.correlation

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean

/** Registers [CorrelationIdWebFilter] in every reactive service and the gateway. */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
class CorrelationAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun correlationIdWebFilter(): CorrelationIdWebFilter = CorrelationIdWebFilter()
}
