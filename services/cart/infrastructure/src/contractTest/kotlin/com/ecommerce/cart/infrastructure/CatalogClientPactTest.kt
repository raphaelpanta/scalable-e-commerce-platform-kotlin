package com.ecommerce.cart.infrastructure

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody
import au.com.dius.pact.consumer.dsl.LambdaDslObject
import au.com.dius.pact.consumer.dsl.PactDslRequestWithPath
import au.com.dius.pact.consumer.dsl.PactDslResponse
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.V4Pact
import au.com.dius.pact.core.model.annotations.Pact
import com.ecommerce.cart.domain.Money
import com.ecommerce.cart.domain.ProductId
import com.ecommerce.cart.domain.ProductQuote
import com.ecommerce.cart.domain.SaleState
import com.ecommerce.cart.infrastructure.catalog.CatalogPricingAdapter
import com.ecommerce.platform.correlation.CorrelationIds
import com.ecommerce.platform.testing.InternalToken
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.reactor.asCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.web.reactive.function.client.WebClient
import reactor.util.context.Context
import java.util.UUID

private const val OK = 200
private const val NOT_FOUND = 404
private const val CORRELATION_ID = "3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13"
private const val CORRELATION_PATTERN = "^[A-Za-z0-9-]{1,64}$"
private const val JSON_PATTERN = "^application/json(;.*)?$"
private const val PROBLEM_PATTERN = "^application/problem\\+json(;.*)?$"
private const val CONTENT_TYPE = "Content-Type"

private const val TRS = "0b4e6d1c-2a57-4c83-9f10-6d8a3e5b7c21"
private const val MUG = "b7e1c4d2-3f58-4a96-8d20-5c1e9a7b3d44"
private const val KETTLE = "a1b2c3d4-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
private const val UNKNOWN = "d4c3b2a1-9f8e-4d7c-8b6a-5f4e3d2c1b0a"
private const val TRS_PRICE = 9490L
private const val MUG_PRICE = 3290L
private const val KETTLE_PRICE = 25900L
private const val TRS_STOCK = 5
private const val KETTLE_STOCK = 3

/** A product of the fixed examples of pact-interactions.md section 1. */
private data class Example(
    val id: String,
    val sku: String,
    val name: String,
    val priceMinor: Long,
    val available: Int,
) {
    val state: Map<String, Any> =
        mapOf(
            "productId" to id,
            "sku" to sku,
            "name" to name,
            "priceMinor" to priceMinor,
            "currency" to "BRL",
            "available" to available,
        )

    fun quote(saleState: SaleState = SaleState.ACTIVE): ProductQuote =
        ProductQuote(ProductId(UUID.fromString(id)), sku, name, Money(priceMinor, "BRL"), available, saleState)

    fun body(
        target: LambdaDslObject,
        saleState: String = "active",
    ) {
        target
            .stringValue("productId", id)
            .stringValue("sku", sku)
            .stringValue("name", name)
            .`object`("price") { price -> price.numberValue("amountMinor", priceMinor).stringValue("currency", "BRL") }
            .numberValue("available", available)
            .stringValue("saleState", saleState)
    }
}

private val SHOES = Example(TRS, "TRS-001", "Trail Running Shoes", TRS_PRICE, TRS_STOCK)
private val MUG_EXAMPLE = Example(MUG, "MUG-CER-01", "Ceramic Mug", MUG_PRICE, 0)
private val KETTLE_EXAMPLE = Example(KETTLE, "KTL-VINT-01", "Vintage Kettle", KETTLE_PRICE, KETTLE_STOCK)

/**
 * Consumer side of cart -> catalog pricing (pact-interactions.md section 2.7): exactly the five interactions of the
 * table, driven through the real [CatalogPricingAdapter] (internal WebClient, `X-Internal-Token`, the caller's
 * `X-Correlation-Id`). Writes `cart-catalog.json` to the repository root `build/pacts`.
 */
@Suppress("TooManyFunctions") // one pact method and one test per interaction of the table
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "catalog", pactVersion = PactSpecVersion.V4)
class CatalogClientPactTest {
    private fun PactDslRequestWithPath.internalHeaders(): PactDslRequestWithPath =
        headers("Accept", "application/json", InternalToken.HEADER, InternalToken.TEST)
            .matchHeader(CorrelationIds.HEADER, CORRELATION_PATTERN, CORRELATION_ID)

    private fun PactDslResponse.jsonHeaders(
        pattern: String = JSON_PATTERN,
        example: String = "application/json",
    ): PactDslResponse =
        matchHeader(CONTENT_TYPE, pattern, example)
            .matchHeader(CorrelationIds.HEADER, CORRELATION_PATTERN, CORRELATION_ID)

    private fun single(
        builder: PactDslWithProvider,
        state: String,
        example: Example,
        description: String,
        saleState: String = "active",
    ): V4Pact =
        builder
            .given(state, example.state)
            .uponReceiving(description)
            .path("/internal/products/${example.id}/pricing")
            .method("GET")
            .internalHeaders()
            .willRespondWith()
            .status(OK)
            .jsonHeaders()
            .body(newJsonBody { example.body(it, saleState) }.build())
            .toPact(V4Pact::class.java)

    @Pact(consumer = "cart")
    fun productInStock(builder: PactDslWithProvider): V4Pact =
        single(builder, "a product exists", SHOES, "a request for the pricing of a product that is in stock")

    @Pact(consumer = "cart")
    fun productOutOfStock(builder: PactDslWithProvider): V4Pact =
        single(builder, "a product exists", MUG_EXAMPLE, "a request for the pricing of a product that is out of stock")

    @Pact(consumer = "cart")
    fun productWithdrawn(builder: PactDslWithProvider): V4Pact =
        single(
            builder,
            "a product is withdrawn",
            KETTLE_EXAMPLE,
            "a request for the pricing of a product that is withdrawn",
            saleState = "withdrawn",
        )

    @Pact(consumer = "cart")
    fun unknownProduct(builder: PactDslWithProvider): V4Pact =
        builder
            .given("no product exists", mapOf("productId" to UNKNOWN))
            .uponReceiving("a request for the pricing of an unknown product")
            .path("/internal/products/$UNKNOWN/pricing")
            .method("GET")
            .internalHeaders()
            .willRespondWith()
            .status(NOT_FOUND)
            .jsonHeaders(PROBLEM_PATTERN, "application/problem+json")
            .body(
                newJsonBody {
                    it
                        .stringValue("type", "https://ecommerce.example/problems/not-found")
                        .stringValue("title", "Not found")
                        .numberValue("status", NOT_FOUND)
                        .stringType("detail", "Product not found.")
                        .stringType("correlationId", CORRELATION_ID)
                }.build(),
            ).toPact(V4Pact::class.java)

    @Pact(consumer = "cart")
    fun severalProducts(builder: PactDslWithProvider): V4Pact =
        builder
            .given("a product exists", SHOES.state)
            .given("a product exists", MUG_EXAMPLE.state)
            .given("no product exists", mapOf("productId" to UNKNOWN))
            .uponReceiving("a request for the pricing of several products")
            .path("/internal/products/pricing")
            .method("POST")
            .internalHeaders()
            .matchHeader(CONTENT_TYPE, JSON_PATTERN, "application/json")
            .body(
                newJsonBody {
                    it.array("productIds") { ids -> ids.stringValue(TRS).stringValue(MUG).stringValue(UNKNOWN) }
                }.build(),
            ).willRespondWith()
            .status(OK)
            .jsonHeaders()
            .body(
                newJsonBody {
                    it.array("items") { items ->
                        items.`object` { item -> SHOES.body(item) }
                        items.`object` { item -> MUG_EXAMPLE.body(item) }
                    }
                }.build(),
            ).toPact(V4Pact::class.java)

    private fun adapter(mockServer: MockServer): CatalogPricingAdapter =
        CatalogPricingAdapter.create(WebClient.builder(), mockServer.getUrl(), InternalToken.TEST)

    /** Runs [block] as a request handler would: with the correlation id in the Reactor context. */
    private fun <T> asRequest(block: suspend () -> T): T =
        runBlocking(Context.of(CorrelationIds.CONTEXT_KEY, CORRELATION_ID).asCoroutineContext()) { block() }

    @Test
    @PactTestFor(pactMethod = "productInStock")
    fun `a product in stock is quoted with its price and availability`(mockServer: MockServer) {
        asRequest { adapter(mockServer).quote(ProductId(UUID.fromString(TRS))) } shouldBe SHOES.quote()
    }

    @Test
    @PactTestFor(pactMethod = "productOutOfStock")
    fun `a product out of stock is quoted with nothing available`(mockServer: MockServer) {
        asRequest { adapter(mockServer).quote(ProductId(UUID.fromString(MUG))) } shouldBe MUG_EXAMPLE.quote()
    }

    @Test
    @PactTestFor(pactMethod = "productWithdrawn")
    fun `a withdrawn product is quoted as withdrawn`(mockServer: MockServer) {
        asRequest { adapter(mockServer).quote(ProductId(UUID.fromString(KETTLE))) } shouldBe
            KETTLE_EXAMPLE.quote(SaleState.WITHDRAWN)
    }

    @Test
    @PactTestFor(pactMethod = "unknownProduct")
    fun `an unknown product has no quote`(mockServer: MockServer) {
        asRequest { adapter(mockServer).quote(ProductId(UUID.fromString(UNKNOWN))) }.shouldBeNull()
    }

    @Test
    @PactTestFor(pactMethod = "severalProducts")
    fun `several products are quoted in one call and unknown ones are absent`(mockServer: MockServer) {
        val ids = listOf(TRS, MUG, UNKNOWN).map { ProductId(UUID.fromString(it)) }

        asRequest { adapter(mockServer).quotes(ids) } shouldBe
            mapOf(SHOES.quote().productId to SHOES.quote(), MUG_EXAMPLE.quote().productId to MUG_EXAMPLE.quote())
    }
}
