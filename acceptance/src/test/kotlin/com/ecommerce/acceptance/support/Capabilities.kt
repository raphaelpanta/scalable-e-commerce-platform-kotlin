package com.ecommerce.acceptance.support

import java.util.UUID

/** One contract operation, ready to send: the `operationId` names it in the OpenAPI files. */
class Operation(
    val operationId: String,
    val method: String,
    val path: String,
    val body: Any? = null,
    val headers: Map<String, String> = emptyMap(),
)

/**
 * The capabilities named in authorisation-sweep.feature mapped to contract operations (SC-010). Ids that the
 * scenario does not own are random: authorisation is decided before any lookup (deny by default, FR-005).
 */
class Capabilities(
    private val productId: String = random(),
    private val categoryId: String = random(),
) {
    fun operation(capability: String): Operation =
        checkNotNull(all()[capability]) { "Unknown capability '$capability'; known: ${all().keys.sorted()}" }

    private fun all(): Map<String, Operation> =
        identity() + catalogue() + carts() + orders() + payments() + notifications()

    private fun identity(): Map<String, Operation> {
        val addressId = random()
        return mapOf(
            "sign out" to Operation("signOut", "DELETE", Paths.CURRENT_SESSION),
            "view the profile" to Operation("getOwnProfile", "GET", Paths.ME),
            "update the profile" to Operation("updateOwnProfile", "PUT", Paths.ME, mapOf("displayName" to "Mallory")),
            "delete the account" to Operation("deleteOwnAccount", "DELETE", Paths.ME),
            "list addresses" to Operation("listOwnAddresses", "GET", Paths.ADDRESSES),
            "add an address" to Operation("addOwnAddress", "POST", Paths.ADDRESSES, Accounts.address("Porto")),
            "update an address" to
                Operation("updateOwnAddress", "PUT", Paths.address(addressId), Accounts.address("Porto")),
            "delete an address" to Operation("deleteOwnAddress", "DELETE", Paths.address(addressId)),
            "view notification preferences" to Operation("getOwnNotificationPreferences", "GET", Paths.PREFERENCES),
            "update notification preferences" to
                Operation("updateOwnNotificationPreferences", "PUT", Paths.PREFERENCES, EMAIL_ONLY),
            "request a phone verification" to
                Operation("requestPhoneVerification", "POST", Paths.PHONE_VERIFICATIONS, mapOf("phoneNumber" to PHONE)),
            "confirm a phone verification" to
                Operation("confirmPhoneVerification", "POST", Paths.PHONE_CONFIRMATION, mapOf("code" to "123456")),
        )
    }

    private fun catalogue(): Map<String, Operation> {
        val product = Catalogue.productBody("Forbidden", "Must never exist.", PRICE, categoryId, 1)
        val stock = mapOf("delta" to 1, "reason" to "Forbidden")
        val image = mapOf("url" to "https://cdn.example.test/forbidden.jpg")
        return mapOf(
            "create a product" to Operation("createProduct", "POST", Paths.PRODUCTS, product),
            "update a product" to Operation("updateProduct", "PUT", Paths.product(productId), product - "initialStock"),
            "change the price of a product" to
                Operation("updateProduct", "PUT", Paths.product(productId), product - "initialStock"),
            "withdraw a product" to Operation("withdrawProduct", "POST", Paths.withdrawal(productId)),
            "adjust stock" to Operation("adjustStock", "POST", Paths.stockAdjustments(productId), stock),
            "add a product image" to Operation("addProductImage", "POST", Paths.images(productId), image),
            "create a category" to Operation("createCategory", "POST", Paths.CATEGORIES, mapOf("name" to "Forbidden")),
            "update a category" to
                Operation("updateCategory", "PUT", Paths.category(categoryId), mapOf("name" to "Forbidden")),
            "withdraw a category" to Operation("withdrawCategory", "POST", Paths.categoryWithdrawal(categoryId)),
        )
    }

    private fun carts(): Map<String, Operation> =
        mapOf(
            "merge a cart" to
                Operation("mergeCart", "POST", Paths.CART_MERGE, headers = mapOf(Headers.CART_TOKEN to random())),
        )

    private fun orders(): Map<String, Operation> {
        val orderId = random()
        val checkout = Checkout.body(random(), "rev-unknown", Cards.APPROVED)
        return mapOf(
            "place an order" to
                Operation("placeOrder", "POST", Paths.ORDERS, checkout, mapOf(Headers.IDEMPOTENCY_KEY to random())),
            "list orders" to Operation("listOwnOrders", "GET", Paths.ORDERS),
            "view an order" to Operation("getOwnOrder", "GET", Paths.order(orderId)),
            "cancel an order" to Operation("cancelOwnOrder", "POST", Paths.cancellation(orderId)),
            "change an order status" to
                Operation("transitionOrderStatus", "POST", Paths.orderStatus(orderId), SHIPPED),
        )
    }

    private fun payments(): Map<String, Operation> {
        val byOrder = "orderId" to random()
        return mapOf(
            "view a payment attempt" to Operation("getPaymentAttempt", "GET", Paths.paymentAttempt(random())),
            "list payment attempts" to
                Operation("listPaymentAttemptsForOrder", "GET", Paths.query(Paths.PAYMENT_ATTEMPTS, byOrder)),
            "list refunds" to Operation("listRefundsForOrder", "GET", Paths.query(Paths.REFUNDS, byOrder)),
            "view a refund" to Operation("getRefund", "GET", Paths.refund(random())),
            "read the payment simulator rules" to Operation("getSimulatorRules", "GET", Paths.SIMULATOR_RULES),
        )
    }

    private fun notifications(): Map<String, Operation> =
        mapOf(
            "list notifications" to Operation("listOwnNotifications", "GET", Paths.NOTIFICATIONS),
            "list failed notifications" to Operation("listFailedNotifications", "GET", Paths.FAILED_NOTIFICATIONS),
            "retry a failed notification" to
                Operation("retryFailedNotification", "POST", Paths.notificationRetry(random())),
        )

    private companion object {
        const val PRICE = 1000L
        const val PHONE = "+5511999990000"
        val EMAIL_ONLY = mapOf("channels" to listOf("email"))
        val SHIPPED = mapOf("orderStatus" to "shipped")

        fun random(): String = UUID.randomUUID().toString()
    }
}
