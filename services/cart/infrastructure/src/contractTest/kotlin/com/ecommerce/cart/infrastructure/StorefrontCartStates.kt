package com.ecommerce.cart.infrastructure

import au.com.dius.pact.provider.ProviderResponse
import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junitsupport.State
import com.ecommerce.platform.testing.JwtFixture
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.apache.hc.client5.http.classic.methods.HttpUriRequest
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.json.JsonMapper

/**
 * The provider states of the storefront pact `storefront-cart.json` (pact-matrix.md K1 to K7), verbatim. The cart
 * database and the catalogue stub are reset before each interaction; each state then creates exactly the carts and
 * the catalogue answers its sentence describes. The catalogue knows the two products of the pact with stock to spare
 * unless a state says otherwise, so every interaction can add, read and merge lines without a state of its own.
 *
 * The storefront's bearer in the pact is a placeholder (the gateway injects the real one at run time): when a request
 * carries one, [httpTarget] replaces it with a shopper token of Ana signed by the JWKS the service trusts, which is
 * the account of every `ana@example.com` state. Abstract: JUnit runs only the verification subclasses.
 */
@Suppress("AbstractClassCanBeConcreteClass", "TooManyFunctions") // one method per provider state
abstract class StorefrontCartStates {
    @Autowired
    protected lateinit var database: DatabaseClient

    private val seeder by lazy { StorefrontCartSeeder(database) }
    private val storefrontCatalog by lazy { StorefrontCatalog(catalog) }

    @BeforeEach
    fun resetStorefrontFixtures() {
        seeder.reset()
        storefrontCatalog.reset()
    }

    /** The HTTP target of the service on [port], signing the bearer of a request that carries one. */
    protected fun httpTarget(port: Int): HttpTestTarget =
        object : HttpTestTarget("localhost", port) {
            override fun executeInteraction(
                client: Any?,
                request: Any?,
            ): ProviderResponse {
                val httpRequest = request as HttpUriRequest
                if (httpRequest.containsHeader(AUTHORIZATION)) {
                    httpRequest.setHeader(AUTHORIZATION, "Bearer " + jwt.tokenFor(StorefrontPact.ANA))
                }
                return super.executeInteraction(client, request)
            }
        }

    @State("an anonymous cart with token tok-cart-1 holds 2 lines")
    fun anonymousCartHoldsTwoLines() = seeder.anonymousCart()

    @State("ana@example.com has an account cart with 1 line")
    fun accountCartWithOneLine() = seeder.accountCart(StorefrontPact.SHOES_QUANTITY)

    @State("no cart exists for token tok-unknown")
    fun noCartForUnknownToken() {
        // Every cart was removed before the interaction.
    }

    @State("no anonymous cart exists")
    fun noAnonymousCart() {
        // Every cart was removed before the interaction.
    }

    @State("product 0b4e6d1c-2a57-4c83-9f10-6d8a3e5b7c21 is active with stock 0")
    fun shoesOutOfStock() = storefrontCatalog.register(StorefrontPact.SHOES.copy(available = 0))

    @State("product 00000000-0000-4000-8000-000000000000 does not exist")
    fun unknownProduct() {
        // The catalogue answers 404 for every product it does not know, and it never knows the nil identifier.
    }

    /** Ana holds 4 pairs of shoes and tok-cart-1 holds 2 more: 6 are requested and the stock of 5 caps the line. */
    @State("an anonymous cart with token tok-cart-1 holds 2 lines and ana@example.com has an account cart")
    fun anonymousAndAccountCarts() {
        seeder.anonymousCart()
        seeder.accountCart(StorefrontPact.ACCOUNT_SHOES_QUANTITY)
    }

    @State("the anonymous cart tok-cart-1 was already merged")
    fun anonymousCartAlreadyMerged() {
        // A merge consumes the anonymous cart: none exists for the token any more.
    }

    @State("a merge for tok-cart-1 is in progress")
    fun mergeInProgress() {
        seeder.anonymousCart()
        seeder.accountCart(StorefrontPact.ACCOUNT_SHOES_QUANTITY)
        seeder.mergeInProgress()
    }

    @State("the price of a line in the cart of ana@example.com changed since it was added")
    fun priceChangedSinceAdded() {
        seeder.accountCart(StorefrontPact.SHOES_QUANTITY)
        val repriced = StorefrontPact.SHOES.copy(priceMinor = StorefrontPact.SHOES_PRICE_AFTER_CHANGE_MINOR)
        storefrontCatalog.register(repriced)
    }

    companion object {
        private const val AUTHORIZATION = "Authorization"
        private val jwt: JwtFixture = JwtFixture()

        internal val mapper: JsonMapper = JsonMapper.builder().build()

        /** The catalogue of every provider state: the cart's internal pricing client talks to this WireMock. */
        internal val catalog: WireMockServer = WireMockServer(options().dynamicPort()).also { it.start() }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("cart.catalog-url") { catalog.baseUrl() }
            registry.add("platform.security.jwks-uri") { jwt.jwksUri }
        }
    }
}
