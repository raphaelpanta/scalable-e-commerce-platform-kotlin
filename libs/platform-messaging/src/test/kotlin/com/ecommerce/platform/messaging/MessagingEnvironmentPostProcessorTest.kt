package com.ecommerce.platform.messaging

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.SpringApplication
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

private fun environment(vararg properties: Pair<String, Any>): StandardEnvironment =
    StandardEnvironment().apply {
        propertySources.addFirst(MapPropertySource("test", properties.toMap()))
        MessagingEnvironmentPostProcessor().postProcessEnvironment(this, SpringApplication())
    }

class MessagingEnvironmentPostProcessorTest :
    FunSpec({
        test("Kafka defaults: bootstrap from KAFKA_BOOTSTRAP_SERVERS, group from the application name") {
            val environment = environment("spring.application.name" to "order")

            environment.getProperty("spring.kafka.bootstrap-servers") shouldBe
                (System.getenv("KAFKA_BOOTSTRAP_SERVERS") ?: "localhost:9092")
            environment.getProperty("spring.kafka.consumer.group-id") shouldBe "order"
            environment.getProperty("spring.kafka.consumer.auto-offset-reset") shouldBe "earliest"
            environment.getProperty("spring.kafka.listener.ack-mode") shouldBe "manual_immediate"
        }

        test("the variable and explicit properties win over the defaults") {
            val environment =
                environment(
                    "KAFKA_BOOTSTRAP_SERVERS" to "kafka:9092",
                    "platform.messaging.consumer.group-id" to "payment",
                    "spring.kafka.consumer.auto-offset-reset" to "latest",
                )

            environment.getProperty("spring.kafka.bootstrap-servers") shouldBe "kafka:9092"
            environment.getProperty("spring.kafka.consumer.group-id") shouldBe "payment"
            environment.getProperty("spring.kafka.consumer.auto-offset-reset") shouldBe "latest"
        }

        test("the defaults are the last property source and are added once") {
            val environment = environment()
            MessagingEnvironmentPostProcessor().postProcessEnvironment(environment, SpringApplication())

            environment.propertySources.last().name shouldBe MessagingEnvironmentPostProcessor.SOURCE_NAME
            environment.propertySources.count { it.name == MessagingEnvironmentPostProcessor.SOURCE_NAME } shouldBe 1
        }
    })
