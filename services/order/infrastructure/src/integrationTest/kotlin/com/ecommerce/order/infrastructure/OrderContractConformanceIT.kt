package com.ecommerce.order.infrastructure

import com.ecommerce.conformance.OpenApiContract
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.test.web.reactive.server.WebTestClient
import java.util.UUID

private const val OK = 200
private const val CREATED = 201
private const val ACCEPTED = 202
private const val BAD_REQUEST = 400
private const val UNAUTHORIZED = 401
private const val FORBIDDEN = 403
private const val NOT_FOUND = 404
private const val CONFLICT = 409
private const val UNPROCESSABLE = 422
private const val UNAVAILABLE = 503
private const val CHANGED_PRICE = 15_900L

/**
 * Every public operation of `contracts/openapi/order.yaml`, with its success and documented error statuses,
 * exercised against the running service (cart, catalog, payment and identity stubbed) and validated request by
 * request (pact-matrix rule 3, constitution Principle V). Statuses the service cannot produce on its own are deferred
 * with the reason.
 */
class OrderContractConformanceIT : OrderIntegrationTest() {
    private val contract = OpenApiContract.of("order")
    private val operatorId: UUID = UUID.randomUUID()

    @Test
    fun `the public operations conform to order yaml`() {
        val shopper = stubs.checkoutOf(Shopper())
        val (paid, pending) = checkout(shopper)
        reads(shopper, paid)
        cancellation(shopper, pending)
        transitions(shopper, paid)
        contract.verify(DEFERRED)
    }

    /** The checkout outcomes; returns the ids of a paid and of a pending order of [shopper]. */
    private fun checkout(shopper: Shopper): Pair<String, String> {
        val key = UUID.randomUUID()
        val paid = contract.check(placeOrder(shopper, key = key), CREATED)["id"].toString()
        contract.check(placeOrder(shopper, key = key), CREATED)
        contract.check(placeOrder(shopper, DECLINED_TOKEN, key), UNPROCESSABLE)
        contract.check(placeOrder(shopper, DECLINED_TOKEN), UNPROCESSABLE)
        val pending = contract.check(placeOrder(shopper, UNREACHABLE_TOKEN), ACCEPTED)["id"].toString()

        val changed = stubs.checkoutOf(Shopper(priceMinor = CHANGED_PRICE), priceAtAdd = Shopper().priceMinor)
        contract.check(placeOrder(changed, revision = "rev-seen-before"), CONFLICT)
        val shortage = stubs.checkoutOf(Shopper(quantity = 2))
        stubs.refuseReservation(shortage)
        contract.check(placeOrder(shortage), CONFLICT)
        val outage = stubs.checkoutOf(Shopper())
        stubs.identityUnavailable(outage)
        contract.check(placeOrder(outage), UNAVAILABLE)

        val body = mapOf("addressId" to shopper.addressId.toString())
        contract.check(request(POST, ORDERS, bearer(shopper.accountId, SHOPPER), body), BAD_REQUEST)
        contract.check(request(POST, ORDERS, null, body, UUID.randomUUID()), UNAUTHORIZED)
        contract.check(request(POST, ORDERS, bearer(operatorId, OPERATOR), body, UUID.randomUUID()), FORBIDDEN)
        return paid to pending
    }

    private fun reads(
        shopper: Shopper,
        paid: String,
    ) {
        val own = bearer(shopper.accountId, SHOPPER)
        contract.check(request(GET, "$ORDERS?page=0&size=10", own), OK)
        contract.check(request(GET, "$ORDERS?size=101", own), BAD_REQUEST)
        contract.check(request(GET, ORDERS, null), UNAUTHORIZED)
        contract.check(request(GET, ORDERS, bearer(operatorId, OPERATOR)), FORBIDDEN)

        contract.check(request(GET, "$ORDERS/$paid", own), OK)
        contract.check(request(GET, "$ORDERS/$paid", bearer(operatorId, OPERATOR)), OK)
        contract.check(request(GET, "$ORDERS/not-a-uuid", own), BAD_REQUEST)
        contract.check(request(GET, "$ORDERS/$paid", null), UNAUTHORIZED)
        contract.check(request(GET, "$ORDERS/$paid", bearer(UUID.randomUUID())), FORBIDDEN)
        contract.check(request(GET, "$ORDERS/$paid", bearer(UUID.randomUUID(), SHOPPER)), NOT_FOUND)
    }

    private fun cancellation(
        shopper: Shopper,
        pending: String,
    ) {
        val own = bearer(shopper.accountId, SHOPPER)
        contract.check(request(POST, "$ORDERS/$pending/cancellation", null), UNAUTHORIZED)
        contract.check(request(POST, "$ORDERS/$pending/cancellation", bearer(operatorId, OPERATOR)), FORBIDDEN)
        contract.check(request(POST, "$ORDERS/${UUID.randomUUID()}/cancellation", own), NOT_FOUND)
        contract.check(request(POST, "$ORDERS/$pending/cancellation", own), OK)
        contract.check(request(POST, "$ORDERS/$pending/cancellation", own), CONFLICT)
    }

    private fun transitions(
        shopper: Shopper,
        paid: String,
    ) {
        val operator = bearer(operatorId, OPERATOR)
        val status = "$ORDERS/$paid/status"
        contract.check(request(POST, status, operator, mapOf("orderStatus" to "preparing")), OK)
        contract.check(request(POST, status, operator, mapOf("orderStatus" to "teleported")), BAD_REQUEST)
        contract.check(request(POST, status, null, mapOf("orderStatus" to "shipped")), UNAUTHORIZED)
        contract.check(
            request(POST, status, bearer(shopper.accountId, SHOPPER), mapOf("orderStatus" to "shipped")),
            FORBIDDEN,
        )
        contract.check(
            request(POST, "$ORDERS/${UUID.randomUUID()}/status", operator, mapOf("orderStatus" to "shipped")),
            NOT_FOUND,
        )
        contract.check(request(POST, status, operator, mapOf("orderStatus" to "placed")), CONFLICT)
    }

    private fun request(
        method: HttpMethod,
        uri: String,
        authorization: String?,
        body: Any? = null,
        idempotencyKey: UUID? = null,
    ): WebTestClient.ResponseSpec {
        val spec =
            client
                .method(method)
                .uri(uri)
                .headers { headers ->
                    authorization?.let { headers.set(AUTHORIZATION, it) }
                    idempotencyKey?.let { headers.set(IDEMPOTENCY_KEY, it.toString()) }
                }
        return (if (body == null) spec else spec.bodyValue(body)).exchange()
    }

    private companion object {
        /** Documented statuses this layer cannot produce, with the reason. */
        val DEFERRED: Map<String, String> =
            mapOf(
                "* 429" to "rate limiting is the gateway's (contracts/gateway-routes.md), covered by its tests",
                "placeOrder 404" to "an unknown address or an empty cart is a 422 (order.yaml); a checkout has no 404",
                "transitionOrderStatus 422" to
                    "the body is one enumerated field: a malformed body is a 400, a refused transition a 409",
                "listOwnOrders 503" to READ_OUTAGE,
                "getOwnOrder 503" to READ_OUTAGE,
                "cancelOwnOrder 503" to READ_OUTAGE,
                "transitionOrderStatus 503" to READ_OUTAGE,
            )

        const val READ_OUTAGE =
            "needs the order database to be unavailable; outage behaviour is covered by the health tests and the " +
                "Compose resilience suite"
    }
}
