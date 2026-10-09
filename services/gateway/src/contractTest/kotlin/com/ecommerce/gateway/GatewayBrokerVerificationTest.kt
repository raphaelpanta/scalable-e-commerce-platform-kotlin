package com.ecommerce.gateway

import au.com.dius.pact.provider.junitsupport.AllowOverridePactUrl
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import au.com.dius.pact.provider.junitsupport.loader.PactBrokerConsumerVersionSelectors
import au.com.dius.pact.provider.junitsupport.loader.SelectorBuilder
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * Provider verification of the gateway against the pacts of the Pact Broker (`pactbroker.url` and the credentials,
 * passed by the `pact` convention when `PACT_BROKER_URL` is set; docs/build.md "Contract tests"); skipped without a
 * broker. Without it the gateway never published a verification result, so the storefront's `can-i-deploy` could not
 * pass ("no such version exists" for gateway). It verifies the consumer versions on their main branch, the deployed or
 * released ones and those on the provider's branch (`pactbroker.providerBranch`, from `PACT_PROVIDER_BRANCH`), or the
 * one pact a broker webhook named (`PACT_URL`, honoured through [AllowOverridePactUrl]); results are published with
 * `pact.provider.version` and the branch when `PACT_PUBLISH_RESULTS=true`. Same provider states as
 * [StorefrontGatewayProviderIT] ([StorefrontProviderStates]).
 */
@Tag("provider")
@Provider("gateway")
@PactBroker
@AllowOverridePactUrl
@IgnoreNoPactsToVerify
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class GatewayBrokerVerificationTest : StorefrontProviderStates() {
    companion object {
        /** Consumer versions on main, deployed or released, and on the provider's own branch when it is known. */
        @JvmStatic
        @PactBrokerConsumerVersionSelectors
        fun consumerVersionSelectors(): SelectorBuilder =
            SelectorBuilder().mainBranch().deployedOrReleased().apply {
                if (!System.getProperty("pactbroker.providerBranch").isNullOrBlank()) matchingBranch()
            }
    }
}
