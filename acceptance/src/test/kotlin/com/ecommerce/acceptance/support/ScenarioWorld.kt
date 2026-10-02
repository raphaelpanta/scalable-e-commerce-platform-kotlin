package com.ecommerce.acceptance.support

import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * State of one scenario, shared by every step class: PicoContainer creates one instance per scenario and injects
 * it into the step classes' constructors. Holds the clients, the actors and what the scenario has created.
 */
class ScenarioWorld {
    val correlationId: String = UUID.randomUUID().toString()
    val api = ApiClient(correlationId)
    val mailpit = MailpitClient()
    val telemetry = Telemetry()
    val accounts = Accounts(api, mailpit)
    val catalogue = Catalogue(api, accounts)
    val carts = Carts(api)
    val orders = Orders(api)

    private val products = mutableMapOf<String, ProductRef>()
    val categories = mutableMapOf<String, String>()

    /** The main shopper of the scenario ("the shopper") and, for two-shopper journeys, "another shopper". */
    var shopper: Shopper? = null
    var otherShopper: Shopper? = null

    /** The anonymous visitor's cart (identified by the issued `X-Cart-Token`). */
    val anonymousCart = CartHolder()

    /** Answer of the cart merge performed when the shopper signed in with an anonymous cart. */
    var mergeResult: ApiResponse? = null

    /** The cart revision the shopper last looked at, sent as `cartRevision` at checkout. */
    var viewedRevision: String? = null

    var checkout: Checkout? = null
    var otherCheckout: Checkout? = null
    var order: JsonNode? = null
    val placedOrderIds = mutableListOf<String>()
    var otherOrderId: String? = null

    /** Correlation id of the last request that was singled out for a central-log lookup. */
    var tracedCorrelationId: String? = null

    fun remember(product: ProductRef) {
        products[product.alias] = product
    }

    fun product(alias: String): ProductRef = checkNotNull(products[alias]) { "No product '$alias' in this scenario" }

    fun latestProduct(): ProductRef? = products.values.lastOrNull()

    fun theShopper(): Shopper = checkNotNull(shopper) { "No shopper in this scenario" }

    fun theOtherShopper(): Shopper = checkNotNull(otherShopper) { "No other shopper in this scenario" }

    fun theCheckout(): Checkout = checkNotNull(checkout) { "No checkout in this scenario" }

    fun orderId(): String = checkNotNull(order?.string("id")) { "No order in this scenario" }

    /** The cart "the shopper" operates: the account cart when signed in, the anonymous cart otherwise. */
    fun shopperCart(): CartHolder =
        shopper?.takeIf { it.isSignedIn }?.let { CartHolder(bearer = it.bearer) } ?: anonymousCart

    fun operator(): String = accounts.operatorToken()
}
