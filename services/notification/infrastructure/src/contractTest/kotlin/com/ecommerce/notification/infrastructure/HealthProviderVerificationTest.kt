package com.ecommerce.notification.infrastructure

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import

/**
 * Provider side: replays every pact of the repository root `build/pacts` (`pact.folder`) against the running
 * notification service. Tagged `provider`, so it runs in `contractVerify`, after every consumer test of the build; with
 * no pact for this provider yet the verification is skipped, not failed.
 */
@Tag("provider")
@Provider("notification")
@PactFolder("\${pact.folder}")
@IgnoreNoPactsToVerify
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = ["management.server.port="])
@Import(PostgresContainerConfig::class)
class HealthProviderVerificationTest(
    @LocalServerPort private val port: Int,
) {
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
