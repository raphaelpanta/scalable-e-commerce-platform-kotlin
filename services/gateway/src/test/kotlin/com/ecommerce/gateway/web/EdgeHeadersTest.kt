package com.ecommerce.gateway.web

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import org.springframework.http.HttpHeaders

private const val STOREFRONT_CSP =
    "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: https:; connect-src 'self'; " +
        "font-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'; object-src 'none'"
private const val API_CSP = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"

/** T021 (research.md section 6): the storefront policy on the shell route only, the API policy everywhere else. */
class EdgeHeadersTest :
    FunSpec({
        test("the storefront route carries the page CSP without unsafe-inline and a restrictive Permissions-Policy") {
            EdgeHeaders.STOREFRONT_CSP shouldBe STOREFRONT_CSP
            EdgeHeaders.STOREFRONT_CSP shouldNotContain "unsafe-inline"
            EdgeHeaders.STOREFRONT_PERMISSIONS_POLICY shouldBe "camera=(), microphone=(), geolocation=(), payment=()"
            EdgeHeaders.policyFor("storefront") shouldBe
                mapOf(
                    "Content-Security-Policy" to STOREFRONT_CSP,
                    "Permissions-Policy" to EdgeHeaders.STOREFRONT_PERMISSIONS_POLICY,
                )
        }

        test("every other route, and no route, keeps the API policy") {
            EdgeHeaders.SECURITY["Content-Security-Policy"] shouldBe API_CSP
            EdgeHeaders.API_CSP shouldBe API_CSP
            EdgeHeaders.policyFor(null).shouldBeEmpty()
            checkAll(
                Arb.string().filter { it != "storefront" },
            ) { routeId -> EdgeHeaders.policyFor(routeId).shouldBeEmpty() }
        }

        test("applying the storefront policy after hardening replaces the CSP and adds Permissions-Policy only") {
            val headers = HttpHeaders()
            headers.set(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
            EdgeHeaders.harden(headers)
            EdgeHeaders.applyRoutePolicy(headers, "storefront")
            headers.getFirst("Content-Security-Policy") shouldBe STOREFRONT_CSP
            headers.getFirst("Permissions-Policy") shouldBe EdgeHeaders.STOREFRONT_PERMISSIONS_POLICY
            headers.getFirst("X-Frame-Options") shouldBe "DENY"
            headers.getFirst("Referrer-Policy") shouldBe "no-referrer"
            headers.getFirst("Strict-Transport-Security") shouldBe "max-age=31536000; includeSubDomains"
            headers.getFirst("X-Content-Type-Options") shouldBe "nosniff"
            headers.getFirst(HttpHeaders.CACHE_CONTROL) shouldBe "public, max-age=31536000, immutable"

            val api = HttpHeaders()
            EdgeHeaders.harden(api)
            EdgeHeaders.applyRoutePolicy(api, "catalog-reads")
            api.getFirst("Content-Security-Policy") shouldBe API_CSP
            api.containsHeader("Permissions-Policy") shouldBe false
            api.headerNames().toList() shouldContainExactly EdgeHeaders.SECURITY.keys.toList()
        }
    })
