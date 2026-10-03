package com.ecommerce.gateway.config

import com.ecommerce.gateway.routing.Tier
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.util.unit.DataSize
import java.net.URI
import java.time.Duration

class GatewayPropertiesTest :
    FunSpec({
        test("defaults follow docs/gateway.md") {
            val properties =
                GatewayProperties(
                    GatewayProperties.Jwt(URI("http://identity/jwks"), issuer = "issuer", audience = "aud"),
                )
            properties.maxBodySize shouldBe DataSize.ofMegabytes(1)
            properties.jwt.issuer shouldBe "issuer"
            properties.jwt.audience shouldBe "aud"
            properties.jwt.cacheTtl shouldBe Duration.ofMinutes(5)
            properties.jwt.refreshCooldown shouldBe Duration.ofSeconds(10)
            properties.jwt.fetchTimeout shouldBe Duration.ofSeconds(2)
            Tier.entries.forEach { tier ->
                properties.rateLimit.limitOf(tier) shouldBe tier.defaultRequestsPerMinute
            }
        }

        test("a configured tier overrides its default, the others keep theirs") {
            val limits = GatewayProperties.RateLimit(mapOf(Tier.AUTH to 100_000))
            limits.limitOf(Tier.AUTH) shouldBe 100_000
            limits.limitOf(Tier.CHECKOUT) shouldBe 20
        }
    })
