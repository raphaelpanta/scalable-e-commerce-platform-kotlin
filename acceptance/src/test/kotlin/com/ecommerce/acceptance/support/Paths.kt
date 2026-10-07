package com.ecommerce.acceptance.support

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Public paths of the OpenAPI files in `specs/004-ecommerce-platform-mvp/contracts/openapi`, named after their
 * `operationId`.
 * This file and the step definitions are the only places of the suite that know the HTTP surface.
 */
object Paths {
    // identity.yaml
    const val ACCOUNTS = "/api/v1/identity/accounts" // registerAccount
    const val VERIFY_EMAIL = "$ACCOUNTS/verify-email" // verifyEmail
    const val SESSIONS = "/api/v1/identity/sessions" // signIn
    const val CURRENT_SESSION = "$SESSIONS/current" // signOut
    const val PASSWORD_RESETS = "/api/v1/identity/password-resets" // requestPasswordReset
    const val PASSWORD_RESET_COMPLETION = "$PASSWORD_RESETS/complete" // completePasswordReset
    const val ME = "$ACCOUNTS/me" // getOwnProfile, updateOwnProfile, deleteOwnAccount
    const val ADDRESSES = "$ME/addresses" // listOwnAddresses, addOwnAddress
    const val PREFERENCES = "$ME/notification-preferences" // get/updateOwnNotificationPreferences
    const val PHONE_VERIFICATIONS = "$ME/phone-verifications" // requestPhoneVerification
    const val PHONE_CONFIRMATION = "$PHONE_VERIFICATIONS/confirm" // confirmPhoneVerification

    fun address(id: String) = "$ADDRESSES/$id" // updateOwnAddress, deleteOwnAddress

    // catalog.yaml
    const val PRODUCTS = "/api/v1/catalog/products" // listProducts, createProduct
    const val CATEGORIES = "/api/v1/catalog/categories" // listCategories, createCategory

    fun product(id: String) = "$PRODUCTS/$id" // getProduct, updateProduct

    fun withdrawal(id: String) = "$PRODUCTS/$id/withdrawal" // withdrawProduct

    fun reinstatement(id: String) = "$PRODUCTS/$id/reinstatement" // reinstateProduct

    fun stockAdjustments(id: String) = "$PRODUCTS/$id/stock-adjustments" // adjustStock

    fun images(id: String) = "$PRODUCTS/$id/images" // addProductImage

    fun category(id: String) = "$CATEGORIES/$id" // getCategory, updateCategory

    fun categoryWithdrawal(id: String) = "$CATEGORIES/$id/withdrawal" // withdrawCategory

    fun categoryReinstatement(id: String) = "$CATEGORIES/$id/reinstatement" // reinstateCategory

    // cart.yaml
    const val CART = "/api/v1/cart" // getCart, clearCart
    const val CART_LINES = "$CART/lines" // addCartLine
    const val CART_MERGE = "$CART/merge" // mergeCart

    fun cartLine(id: String) = "$CART_LINES/$id" // updateCartLineQuantity, removeCartLine

    // order.yaml
    const val ORDERS = "/api/v1/orders" // placeOrder, listOwnOrders

    fun order(id: String) = "$ORDERS/$id" // getOwnOrder

    fun cancellation(id: String) = "$ORDERS/$id/cancellation" // cancelOwnOrder

    fun orderStatus(id: String) = "$ORDERS/$id/status" // transitionOrderStatus

    // payment.yaml
    const val PAYMENT_ATTEMPTS = "/api/v1/payments/attempts" // listPaymentAttemptsForOrder
    const val REFUNDS = "/api/v1/payments/refunds" // listRefundsForOrder
    const val SIMULATOR_RULES = "/api/v1/payments/simulator/rules" // getSimulatorRules

    fun paymentAttempt(id: String) = "$PAYMENT_ATTEMPTS/$id" // getPaymentAttempt

    fun refund(id: String) = "$REFUNDS/$id" // getRefund

    // notification.yaml
    const val NOTIFICATIONS = "/api/v1/notifications" // listOwnNotifications
    const val FAILED_NOTIFICATIONS = "$NOTIFICATIONS/failed" // listFailedNotifications

    fun notificationRetry(id: String) = "$NOTIFICATIONS/$id/retry" // retryFailedNotification

    // telemetry.yaml (feature 005): the storefront's OTLP/HTTP JSON intake
    const val TELEMETRY_TRACES = "/api/v1/telemetry/v1/traces" // exportBrowserTraces
    const val TELEMETRY_LOGS = "/api/v1/telemetry/v1/logs" // exportBrowserLogs

    /** [path] with the query parameters [parameters], URL-encoded. */
    fun query(
        path: String,
        vararg parameters: Pair<String, Any>,
    ): String =
        parameters.joinToString(separator = "&", prefix = "$path?") { (name, value) ->
            "$name=${URLEncoder.encode(value.toString(), StandardCharsets.UTF_8)}"
        }
}

/** Header names of the contracts. */
object Headers {
    const val AUTHORIZATION = "Authorization"
    const val CORRELATION_ID = "X-Correlation-Id"
    const val IDEMPOTENCY_KEY = "Idempotency-Key"
    const val CART_TOKEN = "X-Cart-Token"
    const val RETRY_AFTER = "Retry-After"
    const val BROWSER_SESSION = "X-Browser-Session"
    const val TRACEPARENT = "traceparent"
}

/** Status codes of the contracts. */
object Status {
    const val OK = 200
    const val CREATED = 201
    const val ACCEPTED = 202
    const val NO_CONTENT = 204
    const val BAD_REQUEST = 400
    const val UNAUTHORIZED = 401
    const val FORBIDDEN = 403
    const val NOT_FOUND = 404
    const val CONFLICT = 409
    const val UNPROCESSABLE = 422
    const val TOO_MANY_REQUESTS = 429
}

/** Card tokens of the simulated payment provider (rules: `GET /api/v1/payments/simulator/rules`). */
object Cards {
    const val APPROVED = "tok_sim_approve_4242"
    const val DECLINED = "tok_sim_decline_0001"

    /** Pending on the first attempt; the payment service's retry (about a minute later) approves it. */
    const val UNREACHABLE = "tok_sim_unreachable"

    /** Pending on every attempt, so the payment stays pending until the order's 30-minute expiry. */
    const val UNREACHABLE_FOREVER = "tok_sim_unreachable_forever"
}
