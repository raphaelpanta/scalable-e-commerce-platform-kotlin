package com.ecommerce.payment.infrastructure

import com.ecommerce.conformance.OpenApiContract
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.WebTestClient
import java.util.UUID

private const val OK = 200
private const val BAD_REQUEST = 400
private const val UNAUTHORIZED = 401
private const val FORBIDDEN = 403
private const val NOT_FOUND = 404

/**
 * Every public operation of `contracts/openapi/payment.yaml`, with its success and documented error statuses,
 * exercised against the running service and validated request by request (pact-matrix rule 3, constitution
 * Principle V). Charges and refunds are created through the internal API, as the order service does. Statuses the
 * service cannot produce on its own are deferred with the reason.
 */
class PaymentContractConformanceIT : PaymentIntegrationTest() {
    private val contract = OpenApiContract.of("payment")
    private val operatorId: UUID = UUID.randomUUID()

    @Test
    fun `the public operations conform to payment yaml`() {
        val charge = Charge()
        val attemptId = charged(charge)["attemptId"].toString()
        val refundId = body(postRefund(charge, attemptId).expectStatus().isCreated)["refundId"].toString()
        val declined = Charge(amountMinor = DECLINED_AMOUNT)
        charged(declined)

        val attempt = "$PAYMENTS/attempts/$attemptId"
        contract.check(read(attempt, charge.owner), OK)
        contract.check(read(attempt, operatorId, OPERATOR), OK)
        contract.check(read("$PAYMENTS/attempts/not-a-uuid", charge.owner), BAD_REQUEST)
        contract.check(anonymous(attempt), UNAUTHORIZED)
        contract.check(roleless(attempt), NOT_FOUND)
        contract.check(read(attempt, UUID.randomUUID()), NOT_FOUND)

        val attempts = "$PAYMENTS/attempts?orderId=${declined.orderId}"
        contract.check(read(attempts, declined.owner), OK)
        contract.check(read("$attempts&size=1", operatorId, OPERATOR), OK)
        contract.check(read("$PAYMENTS/attempts", declined.owner), BAD_REQUEST)
        contract.check(anonymous(attempts), UNAUTHORIZED)
        contract.check(roleless(attempts), NOT_FOUND)
        contract.check(read(attempts, UUID.randomUUID()), NOT_FOUND)

        val refunds = "$PAYMENTS/refunds?orderId=${charge.orderId}"
        contract.check(read(refunds, charge.owner), OK)
        contract.check(read("$PAYMENTS/refunds", charge.owner), BAD_REQUEST)
        contract.check(anonymous(refunds), UNAUTHORIZED)
        contract.check(roleless(refunds), NOT_FOUND)
        contract.check(read(refunds, UUID.randomUUID()), NOT_FOUND)

        val refund = "$PAYMENTS/refunds/$refundId"
        contract.check(read(refund, charge.owner), OK)
        contract.check(read(refund, operatorId, OPERATOR), OK)
        contract.check(read("$PAYMENTS/refunds/not-a-uuid", charge.owner), BAD_REQUEST)
        contract.check(anonymous(refund), UNAUTHORIZED)
        contract.check(roleless(refund), NOT_FOUND)
        contract.check(read(refund, UUID.randomUUID()), NOT_FOUND)

        val rules = "$PAYMENTS/simulator/rules"
        contract.check(read(rules, operatorId, OPERATOR), OK)
        contract.check(anonymous(rules), UNAUTHORIZED)
        contract.check(read(rules, UUID.randomUUID()), FORBIDDEN)

        contract.verify(DEFERRED)
    }

    private fun anonymous(path: String): WebTestClient.ResponseSpec = client.get().uri(path).exchange()

    /** A valid token that carries neither `shopper` nor `operator`: treated as a caller who owns nothing. */
    private fun roleless(path: String): WebTestClient.ResponseSpec =
        client
            .get()
            .uri(path)
            .header(AUTHORIZATION, bearer(UUID.randomUUID()))
            .exchange()

    private companion object {
        const val DECLINED_AMOUNT = 4_913L
        const val NO_FORBIDDEN =
            "reads are scoped, not refused: a caller who neither owns the order nor is an operator gets 404 " +
                "(payment.yaml), whatever its roles, so the service never answers 403 here"

        /** Documented statuses this layer cannot produce, with the reason. */
        val DEFERRED: Map<String, String> =
            mapOf(
                "* 429" to "rate limiting is the gateway's (contracts/gateway-routes.md), covered by its tests",
                "getPaymentAttempt 403" to NO_FORBIDDEN,
                "listPaymentAttemptsForOrder 403" to NO_FORBIDDEN,
                "listRefundsForOrder 403" to NO_FORBIDDEN,
                "getRefund 403" to NO_FORBIDDEN,
                "* 503" to "needs the payment database to be unavailable; health and outage behaviour are covered " +
                    "by the health tests and the Compose resilience suite",
            )
    }
}
