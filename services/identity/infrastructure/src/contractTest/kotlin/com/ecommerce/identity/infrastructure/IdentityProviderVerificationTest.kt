package com.ecommerce.identity.infrastructure

import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import org.junit.jupiter.api.Tag

/**
 * Provider verification against the pacts of the repository root `build/pacts` (`pact.folder`), written by the consumer
 * tests of this build. Tagged `provider`, so it runs in `contractVerify` after every `contractTest`; with no pact for
 * `identity` the verification is skipped, not failed. The provider states live in [IdentityProviderStates], shared with
 * [IdentityBrokerVerificationTest].
 */
@Tag("provider")
@Provider("identity")
@PactFolder("\${pact.folder}")
@IgnoreNoPactsToVerify
class IdentityProviderVerificationTest : IdentityProviderStates()
