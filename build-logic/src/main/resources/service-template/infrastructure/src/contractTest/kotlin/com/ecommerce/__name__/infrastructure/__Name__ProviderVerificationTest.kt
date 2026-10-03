package com.ecommerce.__name__.infrastructure

import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import org.junit.jupiter.api.Tag

/**
 * Provider verification against the pacts of the repository root `build/pacts` (`pact.folder`), written by the consumer
 * tests of this build. Tagged `provider`, so it runs in `contractVerify` after every `contractTest`; with no pact for
 * `__name__` the verification is skipped, not failed. The provider states live in [__Name__ProviderStates], shared with
 * [__Name__BrokerVerificationTest].
 */
@Tag("provider")
@Provider("__name__")
@PactFolder("\${pact.folder}")
@IgnoreNoPactsToVerify
class __Name__ProviderVerificationTest : __Name__ProviderStates()
