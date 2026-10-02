package com.ecommerce.platform.messaging

import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

/**
 * Lowest-precedence defaults for `spring.kafka.*`, so that every service connects the same way without
 * repeating them in its application.yml (docs/service-conventions.md §2 and §5). Any value set by the service,
 * the environment or a test (`@ServiceConnection` replaces the bootstrap servers) wins.
 */
class MessagingEnvironmentPostProcessor : EnvironmentPostProcessor {
    override fun postProcessEnvironment(
        environment: ConfigurableEnvironment,
        application: SpringApplication,
    ) {
        if (!environment.propertySources.contains(SOURCE_NAME)) {
            environment.propertySources.addLast(MapPropertySource(SOURCE_NAME, DEFAULTS))
        }
    }

    companion object {
        /** Name of the property source holding the defaults. */
        const val SOURCE_NAME = "platformMessagingDefaults"

        /** The defaults, as placeholders resolved against the rest of the environment. */
        val DEFAULTS: Map<String, Any> =
            mapOf(
                "spring.kafka.bootstrap-servers" to "\${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}",
                "spring.kafka.consumer.group-id" to
                    "\${platform.messaging.consumer.group-id:\${spring.application.name:application}}",
                "spring.kafka.consumer.auto-offset-reset" to "earliest",
                "spring.kafka.consumer.enable-auto-commit" to "false",
                "spring.kafka.producer.acks" to "all",
                "spring.kafka.listener.ack-mode" to "manual_immediate",
            )
    }
}
