package com.ecommerce.order.infrastructure

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDslObject
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.V4Pact
import au.com.dius.pact.core.model.annotations.Pact
import com.ecommerce.order.domain.AccountId
import com.ecommerce.order.domain.Money
import com.ecommerce.order.infrastructure.PactValues.ADA
import com.ecommerce.order.infrastructure.PactValues.BEANS
import com.ecommerce.order.infrastructure.PactValues.CART
import com.ecommerce.order.infrastructure.PactValues.ESPRESSO
import com.ecommerce.order.infrastructure.PactValues.LINE_1
import com.ecommerce.order.infrastructure.PactValues.LINE_2
import com.ecommerce.order.infrastructure.clients.CartClient
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

private const val CART_PATH = "/internal/carts/by-account/$ADA"
private const val ESPRESSO_PRICE = 14_900L
private const val BEANS_PRICE = 2_450L
private const val CART_TOTAL = 19_800L

private val CART_STATE: Map<String, Any> =
    mapOf(
        "accountId" to ADA,
        "cartId" to CART,
        "lines" to
            listOf(
                mapOf(
                    "lineId" to LINE_1,
                    "productId" to ESPRESSO,
                    "sku" to "ESP-MACH-01",
                    "name" to "Espresso Machine",
                    "quantity" to 1,
                    "priceAtAdd" to mapOf("amountMinor" to ESPRESSO_PRICE, "currency" to "BRL"),
                ),
                mapOf(
                    "lineId" to LINE_2,
                    "productId" to BEANS,
                    "sku" to "CB-1KG",
                    "name" to "Coffee Beans 1kg",
                    "quantity" to 2,
                    "priceAtAdd" to mapOf("amountMinor" to BEANS_PRICE, "currency" to "BRL"),
                ),
            ),
    )

@Suppress("LongParameterList") // one cart line of the provider state, field by field
private fun cartLine(
    line: LambdaDslObject,
    lineId: String,
    productId: String,
    sku: String,
    name: String,
    quantity: Int,
    priceMinor: Long,
) {
    line.stringValue("lineId", lineId)
    line.stringValue("productId", productId)
    line.stringValue("sku", sku)
    line.stringValue("name", name)
    line.numberValue("quantity", quantity)
    line.money("priceAtAdd", priceMinor)
}

/** Consumer side of order -> cart (pact-interactions.md section 2.2), through the real [CartClient]. */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "cart", pactVersion = PactSpecVersion.V4)
class CartConsumerPactTest {
    @Pact(consumer = "order")
    fun cartOfAnAccount(builder: PactDslWithProvider): V4Pact =
        builder
            .given("an account cart exists", CART_STATE)
            .internalRequest("a request for the cart of an account", "GET", CART_PATH)
            .jsonAnswer(
                PactValues.OK,
                json { body ->
                    body.stringValue("cartId", CART)
                    body.stringType("revision", "rev-7f3a9c21")
                    body.array("lines") { lines ->
                        lines.`object` {
                            cartLine(
                                it,
                                LINE_1,
                                ESPRESSO,
                                "ESP-MACH-01",
                                "Espresso Machine",
                                1,
                                ESPRESSO_PRICE,
                            )
                        }
                        lines.`object` { cartLine(it, LINE_2, BEANS, "CB-1KG", "Coffee Beans 1kg", 2, BEANS_PRICE) }
                    }
                    body.money("total", CART_TOTAL)
                },
            ).toPact(V4Pact::class.java)

    @Pact(consumer = "order")
    fun emptyCart(builder: PactDslWithProvider): V4Pact =
        builder
            .given("an empty account cart exists", mapOf("accountId" to ADA, "cartId" to CART))
            .internalRequest("a request for the cart of an account with an empty cart", "GET", CART_PATH)
            .jsonAnswer(
                PactValues.OK,
                json { body ->
                    body.stringValue("cartId", CART)
                    body.stringType("revision", "rev-0c11d2e3")
                    body.array("lines") { }
                    body.money("total", 0)
                },
            ).toPact(V4Pact::class.java)

    @Pact(consumer = "order")
    fun noCart(builder: PactDslWithProvider): V4Pact =
        builder
            .given("no cart exists for the account", mapOf("accountId" to ADA))
            .internalRequest("a request for the cart of an account that has no cart", "GET", CART_PATH)
            .problemAnswer(PactValues.NOT_FOUND, "not-found", "Not found", "No cart exists for the account.")
            .toPact(V4Pact::class.java)

    @Pact(consumer = "order")
    fun clearCart(builder: PactDslWithProvider): V4Pact =
        builder
            .given("an account cart exists", CART_STATE)
            .internalRequest("a request to clear the cart of an account", "POST", "$CART_PATH/clear")
            .noContent()
            .toPact(V4Pact::class.java)

    @Pact(consumer = "order")
    fun clearMissingCart(builder: PactDslWithProvider): V4Pact =
        builder
            .given("no cart exists for the account", mapOf("accountId" to ADA))
            .internalRequest("a request to clear the cart of an account that has no cart", "POST", "$CART_PATH/clear")
            .noContent()
            .toPact(V4Pact::class.java)

    @Test
    @PactTestFor(pactMethod = "cartOfAnAccount")
    fun `the cart is read with its revision and lines`(mockServer: MockServer) {
        val cart = checkNotNull(withPactCorrelation { client(mockServer).cartOf(ACCOUNT) })

        cart.revision shouldBe "rev-7f3a9c21"
        cart.lines.map { it.productId.toString() } shouldBe listOf(ESPRESSO, BEANS)
        cart.lines.map { it.quantity.value } shouldBe listOf(1, 2)
        cart.lines.first().priceAtAdd shouldBe Money(ESPRESSO_PRICE, "BRL")
        cart.lines.first().lineId shouldBe LINE_1
    }

    @Test
    @PactTestFor(pactMethod = "emptyCart")
    fun `an empty cart has no lines`(mockServer: MockServer) {
        checkNotNull(withPactCorrelation { client(mockServer).cartOf(ACCOUNT) }).lines.shouldBeEmpty()
    }

    @Test
    @PactTestFor(pactMethod = "noCart")
    fun `an account without a cart has none`(mockServer: MockServer) {
        withPactCorrelation { client(mockServer).cartOf(ACCOUNT) } shouldBe null
    }

    @Test
    @PactTestFor(pactMethod = "clearCart")
    fun `the cart is cleared`(mockServer: MockServer) {
        withPactCorrelation { client(mockServer).clear(ACCOUNT) }
    }

    @Test
    @PactTestFor(pactMethod = "clearMissingCart")
    fun `clearing a missing cart succeeds`(mockServer: MockServer) {
        withPactCorrelation { client(mockServer).clear(ACCOUNT) }
    }

    private fun client(mockServer: MockServer) = CartClient(internalClient(mockServer))

    private companion object {
        val ACCOUNT = AccountId(UUID.fromString(ADA))
    }
}
