package com.ecommerce.notification.infrastructure

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.State
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import

/**
 * Provider side of every pact whose provider is `notification`, against the running service. Today that is the platform
 * probe's health pact (no other service calls notification over HTTP). Shared by [NotificationProviderVerificationTest]
 * (pacts of `build/pacts`) and [NotificationBrokerVerificationTest] (pacts of the Pact Broker), which only choose the
 * pact source; both are tagged `provider` and run in `contractVerify`.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = ["management.server.port="])
@Import(PostgresContainerConfig::class, ContractTestConfig::class)
// Abstract: JUnit runs only the subclasses, which choose the pact source (folder or broker).
@Suppress("AbstractClassCanBeConcreteClass")
abstract class NotificationProviderStates {
    @LocalServerPort
    protected var port: Int = 0

    @BeforeEach
    fun target(context: PactVerificationContext?) {
        context?.target = HttpTestTarget("localhost", port)
    }

    @State("the notification service is running")
    fun serviceRunning() {
        // The Spring context and its database are already up; nothing to arrange.
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun serviceHonoursItsConsumers(context: PactVerificationContext?) {
        context?.verifyInteraction()
    }
}
