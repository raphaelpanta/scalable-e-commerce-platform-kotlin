package com.ecommerce.payment.infrastructure

import arrow.core.getOrElse
import com.ecommerce.conformance.OpenApiContract
import com.ecommerce.payment.application.PaymentLedger
import com.ecommerce.payment.domain.AccountId
import com.ecommerce.payment.domain.ChargeRequest
import com.ecommerce.payment.domain.IdempotencyKey
import com.ecommerce.payment.domain.Money
import com.ecommerce.payment.domain.OrderId
import com.ecommerce.payment.domain.PaymentAttempt
import com.ecommerce.payment.domain.PaymentAttemptId
import com.ecommerce.payment.domain.PaymentMethodRef
import com.ecommerce.payment.domain.ProviderDecision
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.reactor.mono
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration
import java.time.Instant
import java.util.UUID

private const val OK = 200
private const val BAD_REQUEST = 400
private const val UNAUTHORIZED = 401
private const val FORBIDDEN = 403
private const val NOT_FOUND = 404
private val STORE_TIMEOUT: Duration = Duration.ofSeconds(10)

/**
 * Every public operation of `contracts/openapi/payment.yaml`, with its success and documented error statuses,
 * exercised against the running service and validated request by request (pact-matrix rule 3, constitution
 * Principle V). Charges and refunds are created through the internal API, as the order service does. Statuses the
 * service cannot produce on its own are deferred with the reason.
 */
class PaymentContractConformanceIT : PaymentIntegrationTest() {
    private val contract = OpenApiContract.of("payment")
    private val operatorId: UUID = UUID.randomUUID()

    @Autowired
    lateinit var ledger: PaymentLedger

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

        unresolvedAttempts()

        contract.verify(DEFERRED)
    }

    /**
     * `PaymentAttempt` while unresolved (data-model §3.5): a pending attempt (provider unreachable) and a voided one
     * (superseded by its retry) carry no `providerReference`, and both conform. They are created now, so the retry job
     * (60-second delay) leaves them as they are during the test.
     */
    private fun unresolvedAttempts() {
        val pending = Charge(token = UNREACHABLE_FOREVER_TOKEN)
        val pendingId = charged(pending)["attemptId"].toString()

        val superseded = Charge(token = UNREACHABLE_FOREVER_TOKEN)
        val first = firstAttempt(superseded)
        val retry = first.retry(PaymentAttemptId(UUID.randomUUID()), ProviderDecision.Unreachable, Instant.now())
        listOf(first.void(), retry).forEach(::store)

        val pendingBody = attempt(pendingId, pending.owner)
        pendingBody["outcome"] shouldBe "pending"
        pendingBody.containsKey("providerReference") shouldBe false
        val voidedBody = attempt(first.id.toString(), superseded.owner)
        voidedBody["outcome"] shouldBe "voided"
        voidedBody.containsKey("providerReference") shouldBe false
        attempt(retry.id.toString(), superseded.owner)["retryOf"] shouldBe first.id.toString()

        contract.check(read("$PAYMENTS/attempts/$pendingId", operatorId, OPERATOR), OK)
        contract.check(read("$PAYMENTS/attempts?orderId=${superseded.orderId}", superseded.owner), OK)
    }

    /** `GET` attempt [id] as [owner], checked against the contract, and its body. */
    private fun attempt(
        id: String,
        owner: UUID,
    ): Map<String, Any?> = contract.check(read("$PAYMENTS/attempts/$id", owner), OK)

    /** The first, pending attempt of [charge] as an unreachable provider leaves it (not stored). */
    private fun firstAttempt(charge: Charge): PaymentAttempt =
        PaymentAttempt.charge(
            PaymentAttemptId(UUID.randomUUID()),
            ChargeRequest(
                OrderId(charge.orderId),
                AccountId(charge.owner),
                Money(charge.amountMinor, "BRL"),
                PaymentMethodRef.of(charge.token).getOrElse { error(it) },
                IdempotencyKey(charge.key),
            ),
            ProviderDecision.Unreachable,
            Instant.now(),
        )

    private fun store(attempt: PaymentAttempt) {
        check(mono { ledger.attempts.insert(attempt) }.block(STORE_TIMEOUT) == true) { "attempt not stored" }
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
