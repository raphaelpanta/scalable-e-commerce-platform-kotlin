package com.ecommerce.__name__.infrastructure

import au.com.dius.pact.provider.junitsupport.AllowOverridePactUrl
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import au.com.dius.pact.provider.junitsupport.loader.PactBrokerConsumerVersionSelectors
import au.com.dius.pact.provider.junitsupport.loader.SelectorBuilder
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * Provider verification against the pacts of the Pact Broker (`pactbroker.url` and the credentials, passed by the
 * `pact` convention when `PACT_BROKER_URL` is set; docs/build.md "Contract tests"); skipped without a broker. It
 * verifies the consumer versions on their main branch, the deployed or released ones and those on the provider's branch
 * (`pactbroker.providerBranch`, from `PACT_PROVIDER_BRANCH`). A run that a broker webhook triggered for one changed
 * pact verifies only that pact (`PACT_URL` and `PACT_CONSUMER`: `pact.filter.pacturl` and `pact.filter.consumers`,
 * honoured through [AllowOverridePactUrl]). The results are published with `pact.provider.version` and the branch when
 * `PACT_PUBLISH_RESULTS=true`; a failure names the consumer and the interaction. Same provider states as
 * [__Name__ProviderVerificationTest] ([__Name__ProviderStates]).
 */
@Tag("provider")
@Provider("__name__")
@PactBroker
@AllowOverridePactUrl
@IgnoreNoPactsToVerify
@EnabledIfSystemProperty(named = "pactbroker.url", matches = ".+")
class __Name__BrokerVerificationTest : __Name__ProviderStates() {
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
