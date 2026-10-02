package com.ecommerce.gateway.routing

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.util.unit.DataSize

private val shopper = Caller("7c1d4f3e-0a52-4c0b-8d3a-1e2f3a4b5c6d", setOf(Roles.SHOPPER))
private val operator = Caller("e5a1c3b7-2d40-4f9e-8b16-3c7d9a0f1e22", setOf(Roles.OPERATOR))
private val roleless = Caller("9e8d7c6b-5a49-4382-9f1e-0d2c4b6a8e10", emptySet())

class RoutePolicyTest :
    FunSpec({
        context("access decisions") {
            data class Case(
                val requirement: AuthRequirement,
                val caller: Caller?,
                val decision: AccessDecision,
            )
            withData(
                Case(AuthRequirement.ANONYMOUS, null, AccessDecision.GRANTED),
                Case(AuthRequirement.ANONYMOUS, roleless, AccessDecision.GRANTED),
                Case(AuthRequirement.AUTHENTICATED, null, AccessDecision.UNAUTHENTICATED),
                Case(AuthRequirement.AUTHENTICATED, roleless, AccessDecision.GRANTED),
                Case(AuthRequirement.AUTHENTICATED, operator, AccessDecision.GRANTED),
                Case(AuthRequirement.SHOPPER, null, AccessDecision.UNAUTHENTICATED),
                Case(AuthRequirement.SHOPPER, shopper, AccessDecision.GRANTED),
                Case(AuthRequirement.SHOPPER, operator, AccessDecision.FORBIDDEN),
                Case(AuthRequirement.OPERATOR, null, AccessDecision.UNAUTHENTICATED),
                Case(AuthRequirement.OPERATOR, shopper, AccessDecision.FORBIDDEN),
                Case(AuthRequirement.OPERATOR, operator, AccessDecision.GRANTED),
                Case(
                    AuthRequirement.OPERATOR,
                    operator.copy(roles = setOf("shopper", "operator")),
                    AccessDecision.GRANTED,
                ),
            ) { (requirement, caller, decision) -> requirement.decide(caller) shouldBe decision }
        }

        test("metadata is parsed into a policy") {
            RoutePolicy.from(
                "images",
                mapOf(
                    "auth" to "operator",
                    "tier" to "operator",
                    "max-body-size" to "5MB",
                    "response-timeout" to 15000,
                ),
            ) shouldBe RoutePolicy(AuthRequirement.OPERATOR, Tier.OPERATOR, maxBodySize = DataSize.ofMegabytes(5))
        }

        test("the operator tier applies to operators only") {
            val policy =
                RoutePolicy.from(
                    "order-read",
                    mapOf(
                        "auth" to "authenticated",
                        "tier" to "standard",
                        "operator-tier" to "operator",
                        "response-timeout" to "10000",
                    ),
                )
            policy.tierFor(null) shouldBe Tier.STANDARD
            policy.tierFor(shopper) shouldBe Tier.STANDARD
            policy.tierFor(operator) shouldBe Tier.OPERATOR
        }

        context("invalid metadata stops the gateway") {
            withData(
                mapOf("tier" to "browse", "response-timeout" to 5000) to "metadata 'auth' is required",
                mapOf("auth" to " ", "tier" to "browse", "response-timeout" to 5000) to "metadata 'auth' is required",
                mapOf("auth" to "anonymous", "response-timeout" to 5000) to "metadata 'tier' is required",
                mapOf("auth" to "everyone", "tier" to "browse", "response-timeout" to 5000) to
                    "unknown auth requirement 'everyone'",
                mapOf("auth" to "anonymous", "tier" to "gold", "response-timeout" to 5000) to
                    "unknown rate-limit tier 'gold'",
                mapOf("auth" to "anonymous", "tier" to "browse") to "metadata 'response-timeout' is required",
                mapOf("auth" to "anonymous", "tier" to "browse", "response-timeout" to 30000) to
                    "differs from the browse tier's 5000 ms",
                mapOf("auth" to "anonymous", "tier" to "browse", "response-timeout" to "soon") to "soon",
                mapOf(
                    "auth" to "anonymous",
                    "tier" to "browse",
                    "response-timeout" to 5000,
                    "max-body-size" to "big",
                ) to
                    "big",
            ) { (metadata, message) ->
                val failure = shouldThrow<IllegalArgumentException> { RoutePolicy.from("broken", metadata) }
                failure.message shouldContain "route 'broken'"
                failure.message shouldContain message
            }
        }

        test("tier defaults follow gateway-routes.md") {
            Tier.entries.associate { it.id to it.defaultRequestsPerMinute } shouldBe
                mapOf("auth" to 10, "browse" to 600, "standard" to 120, "checkout" to 20, "operator" to 300)
            Tier.AUTH.clientKey shouldBe ClientKey.SOURCE_ADDRESS
            Tier.CHECKOUT.clientKey shouldBe ClientKey.ACCOUNT_OR_ADDRESS
        }
    })
