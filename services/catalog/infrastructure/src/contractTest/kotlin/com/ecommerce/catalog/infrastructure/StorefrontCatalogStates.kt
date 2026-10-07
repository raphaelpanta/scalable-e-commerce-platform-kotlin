package com.ecommerce.catalog.infrastructure

import au.com.dius.pact.provider.ProviderResponse
import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junitsupport.State
import com.ecommerce.platform.testing.JwtFixture
import org.apache.hc.client5.http.classic.methods.HttpUriRequest
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.UUID

/** A signed-in account of a provider state: the bearer the verifier sends is replaced by a token for it. */
private data class SignedIn(
    val accountId: UUID,
    val roles: Set<String>,
)

private val OPERATOR = SignedIn(StorefrontPact.OPS, setOf("operator"))
private val SHOPPER = SignedIn(StorefrontPact.ANA, setOf("shopper"))

/**
 * The provider states of the storefront pact `storefront-catalog.json` (pact-matrix.md C1 to C7), verbatim, C6 and C7
 * included although the storefront does not send them yet. Abstract: JUnit runs only the verification
 * subclasses. The states describe the catalogue the shopper sees; they
 * run after [CatalogProviderStates] emptied the tables, so each creates exactly what its sentence says.
 *
 * The storefront's bearer in the pact is a placeholder (the gateway injects the real one at run time). When a request
 * carries one, [httpTarget] replaces it with a token signed by the JWKS the service trusts: for the account of the
 * `... is signed in` state, an operator when no state says otherwise (the console is the only caller with a token).
 */
@Suppress("TooManyFunctions", "AbstractClassCanBeConcreteClass")
abstract class StorefrontCatalogStates {
    @Autowired
    protected lateinit var database: DatabaseClient

    private var signedIn: SignedIn = OPERATOR
    private val seeder by lazy { StorefrontCatalogSeeder(database) }

    @BeforeEach
    fun forgetSignedIn() {
        signedIn = OPERATOR
    }

    /** The HTTP target of the service on [port]; a bearer the pact carries is replaced by the one of the state. */
    protected fun httpTarget(port: Int): HttpTestTarget =
        object : HttpTestTarget("localhost", port) {
            override fun executeInteraction(
                client: Any?,
                request: Any?,
            ): ProviderResponse {
                val httpRequest = request as HttpUriRequest
                if (httpRequest.containsHeader(AUTHORIZATION)) {
                    httpRequest.setHeader(AUTHORIZATION, "Bearer " + jwt.tokenFor(signedIn.accountId, signedIn.roles))
                }
                return super.executeInteraction(client, request)
            }
        }

    @State("an operator ops@example.com is signed in")
    fun operatorSignedIn() {
        signedIn = OPERATOR
    }

    @State("a shopper ana@example.com is signed in")
    fun shopperSignedIn() {
        signedIn = SHOPPER
    }

    @State("the catalogue has 25 active products")
    fun twentyFiveActiveProducts() = seeder.numberedProducts(StorefrontPact.ACTIVE_PRODUCTS)

    @State("the catalogue has 25 active products and 2 withdrawn")
    fun twentyFiveActiveAndTwoWithdrawn() {
        seeder.numberedProducts(StorefrontPact.ACTIVE_PRODUCTS)
        seeder.numberedProducts(
            StorefrontPact.WITHDRAWN_PRODUCTS,
            saleState = "withdrawn",
            firstNumber = StorefrontPact.ACTIVE_PRODUCTS + 1,
        )
    }

    @State("the catalogue has no products")
    fun noProducts() {
        // Every table is empty before the interaction.
    }

    @State("the catalogue has active products matching \"shoes\"")
    fun productsMatchingShoes() {
        seeder.product(SeededProduct.shoes(StorefrontPact.STOCK_IN))
        seeder.numberedProducts(1)
        listOf("Everyday Shoes", "Shoes Care Kit").forEachIndexed { index, name ->
            seeder.product(
                SeededProduct(UUID.nameUUIDFromBytes("shoes-$index".toByteArray()), "PACT-SHOES-$index", name),
            )
        }
    }

    @State("category 5c2f9a7e-1b34-4d68-8a0e-3f6c1d9b2e45 has 3 products")
    fun footwearHasThreeProducts() = seeder.numberedProducts(StorefrontPact.CATEGORY_PRODUCTS)

    @State("the catalogue has 3 categories")
    fun threeCategories() {
        seeder.clearCategories()
        seeder.footwear()
        seeder.category(UUID.nameUUIDFromBytes("apparel".toByteArray()), "Apparel", "Clothes for every season")
        seeder.category(UUID.nameUUIDFromBytes("accessories".toByteArray()), "Accessories", "Bags, belts and caps")
    }

    @State("the catalogue has no categories")
    fun noCategories() = seeder.clearCategories()

    @State("category 5c2f9a7e-1b34-4d68-8a0e-3f6c1d9b2e45 exists")
    fun footwearExists() = seeder.footwear()

    @State("category 00000000-0000-4000-8000-000000000000 does not exist")
    fun unknownCategory() {
        // The nil identifier is never created.
    }

    @State("product 0b4e6d1c-2a57-4c83-9f10-6d8a3e5b7c21 is active with stock 5")
    fun shoesInStock() = seeder.product(SeededProduct.shoes(StorefrontPact.STOCK_IN))

    @State("product 0b4e6d1c-2a57-4c83-9f10-6d8a3e5b7c21 is active with stock 0")
    fun shoesOutOfStock() = seeder.product(SeededProduct.shoes(stock = 0))

    @State("product 0b4e6d1c-2a57-4c83-9f10-6d8a3e5b7c21 is withdrawn")
    fun shoesWithdrawn() = seeder.product(SeededProduct.shoes(StorefrontPact.STOCK_IN, saleState = "withdrawn"))

    @State("product 00000000-0000-4000-8000-000000000000 does not exist")
    fun unknownProduct() {
        // The nil identifier is never created.
    }

    companion object {
        private const val AUTHORIZATION = "Authorization"
        private val jwt: JwtFixture = JwtFixture()

        @JvmStatic
        @DynamicPropertySource
        fun trustTheTestJwks(registry: DynamicPropertyRegistry) {
            registry.add("platform.security.jwks-uri") { jwt.jwksUri }
        }
    }
}
