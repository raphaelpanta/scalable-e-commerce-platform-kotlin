package com.ecommerce.gateway.routing

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import org.springframework.cloud.gateway.support.ShortcutConfigurable.ShortcutType
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange

/** The `NotPath` predicate of the storefront catch-all: true outside every excluded prefix. */
class NotPathRoutePredicateFactoryTest :
    FunSpec({
        val factory = NotPathRoutePredicateFactory()
        val predicate =
            factory.apply(
                NotPathRoutePredicateFactory.Config().apply {
                    patterns = listOf("/api/**", " /actuator/** ", "/.well-known/**")
                },
            )

        fun accepts(path: String): Boolean = predicate.test(MockServerWebExchange.from(MockServerHttpRequest.get(path)))

        test("the shortcut gathers every comma-separated pattern into `patterns`") {
            factory.shortcutFieldOrder() shouldBe listOf("patterns")
            factory.shortcutType() shouldBe ShortcutType.GATHER_LIST
        }

        test("paths under an excluded prefix, the prefix itself included, are refused") {
            listOf("/api", "/api/", "/api/v1/unknown", "/actuator/health", "/.well-known/jwks.json", "/actuator")
                .forEach { path -> accepts(path) shouldBe false }
        }

        test("every other path is accepted") {
            listOf("/", "/products/1", "/assets/app.js", "/apix", "/api-docs", "/cart?x=1").forEach { path ->
                accepts(path) shouldBe true
            }
            checkAll(Arb.element("/", "/products/", "/assets/"), Arb.string()) { prefix, rest ->
                val path = prefix + rest.filter { it.isLetterOrDigit() }
                accepts(path) shouldBe true
            }
        }
    })
