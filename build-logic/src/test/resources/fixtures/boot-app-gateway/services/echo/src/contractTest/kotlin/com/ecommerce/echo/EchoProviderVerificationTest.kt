package com.ecommerce.echo

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith

@Tag("provider")
@Provider("echo")
@PactFolder("\${pact.folder}")
@IgnoreNoPactsToVerify
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EchoProviderVerificationTest {
    private val server: HttpServer = startEcho("""{"message":"hello"}""")

    @AfterAll
    fun stop() {
        server.stop(0)
    }

    @BeforeEach
    fun target(context: PactVerificationContext?) {
        context?.target = HttpTestTarget("localhost", server.address.port)
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun echoHonoursItsConsumers(context: PactVerificationContext?) {
        context?.verifyInteraction()
    }
}
