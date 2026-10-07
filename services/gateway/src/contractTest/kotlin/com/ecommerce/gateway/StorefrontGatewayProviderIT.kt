package com.ecommerce.gateway

import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import java.io.File

/**
 * Provider verification of the storefront's gateway pact (feature 005, pact-matrix.md "Storefront to gateway") from
 * the repository root `build/pacts` (`pact.folder`), written by the storefront's Pact consumer test (T037). Tagged
 * `provider`, so it runs in `contractVerify` after every `contractTest`. Until the storefront consumer has produced
 * `storefront-gateway.json` the class is skipped with that message (an assumption, before the gateway boots); with no
 * pact for `gateway` at all the verification is skipped, not failed. The provider states live in
 * [StorefrontProviderStates].
 */
@Tag("provider")
@Provider("gateway")
@PactFolder("\${pact.folder}")
@IgnoreNoPactsToVerify
class StorefrontGatewayProviderIT : StorefrontProviderStates() {
    companion object {
        private const val PACT_FILE = "storefront-gateway.json"

        @JvmStatic
        @BeforeAll
        fun requireTheStorefrontPact() {
            val folder = System.getProperty("pact.folder").orEmpty()
            val pact = File(folder, PACT_FILE)
            assumeTrue(pact.isFile) {
                "$PACT_FILE is absent from $folder: the storefront consumer test (frontend/pact, T037) has not " +
                    "written it yet, so the gateway provider verification is skipped"
            }
        }
    }
}
