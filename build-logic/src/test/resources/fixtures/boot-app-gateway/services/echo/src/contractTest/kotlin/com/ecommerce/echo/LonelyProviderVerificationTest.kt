package com.ecommerce.echo

import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith

/** A provider no consumer has written a pact for yet. */
@Tag("provider")
@Provider("lonely")
@PactFolder("\${pact.folder}")
@IgnoreNoPactsToVerify
class LonelyProviderVerificationTest {
    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun lonelyHonoursItsConsumers(context: PactVerificationContext?) {
        context?.verifyInteraction()
    }
}
