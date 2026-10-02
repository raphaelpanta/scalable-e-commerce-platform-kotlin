package com.ecommerce.platform.problem

import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import tools.jackson.databind.json.JsonMapper

/** Registers the problem writer and the `application/problem+json` exception handler of every reactive service. */
@AutoConfiguration(afterName = ["org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration"])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
class PlatformProblemAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun problemWriter(jsonMapper: ObjectProvider<JsonMapper>): ProblemWriter =
        ProblemWriter(jsonMapper.getIfAvailable { JsonMapper.builder().findAndAddModules().build() })

    @Bean
    @ConditionalOnMissingBean
    fun problemExceptionHandler(writer: ProblemWriter): ProblemExceptionHandler = ProblemExceptionHandler(writer)
}
