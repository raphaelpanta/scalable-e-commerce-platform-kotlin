package com.ecommerce.order.infrastructure

import au.com.dius.pact.core.model.Interaction
import au.com.dius.pact.core.model.Pact
import au.com.dius.pact.provider.junit5.HttpTestTarget
import org.apache.hc.core5.http.HttpRequest
import org.springframework.http.HttpHeaders

/**
 * The HTTP target of the provider verification. The storefront pacts carry the placeholder bearer the gateway stands
 * for (pact-matrix.md "What the service pacts cover"), so a replayed request that has an `Authorization` header is
 * sent with a test token of the caller the current provider state signed in; requests without one (health, internal
 * calls) are sent unchanged. A target subclass, not a test-method parameter, so that the message interactions of the
 * same pact source keep working.
 */
class BearerRewritingTarget(
    port: Int,
    private val bearer: () -> String,
) : HttpTestTarget("localhost", port) {
    override fun prepareRequest(
        pact: Pact,
        interaction: Interaction,
        context: MutableMap<String, Any>,
    ): Pair<Any, Any>? {
        val prepared = super.prepareRequest(pact, interaction, context)
        val request = prepared?.first as? HttpRequest
        if (request?.containsHeader(HttpHeaders.AUTHORIZATION) == true) {
            request.setHeader(HttpHeaders.AUTHORIZATION, bearer())
        }
        return prepared
    }
}
