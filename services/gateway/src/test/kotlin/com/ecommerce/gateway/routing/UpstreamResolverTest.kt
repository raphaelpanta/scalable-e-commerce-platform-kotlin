package com.ecommerce.gateway.routing

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import reactor.netty.http.client.HttpClient
import java.time.Duration

class UpstreamResolverTest :
    FunSpec({
        test("upstream names are cached for at most 5 seconds and their addresses used round-robin") {
            listOf(
                UpstreamResolver.configure(HttpClient.create()),
                UpstreamHttpClientConfiguration().upstreamResolver().customize(HttpClient.create()),
            ).forEach { client ->
                val resolver = client.configuration().nameResolverProvider.shouldNotBeNull()
                resolver.cacheMaxTimeToLive() shouldBe Duration.ofSeconds(5)
                resolver.isRoundRobinSelection.shouldBeTrue()
            }
        }
    })
