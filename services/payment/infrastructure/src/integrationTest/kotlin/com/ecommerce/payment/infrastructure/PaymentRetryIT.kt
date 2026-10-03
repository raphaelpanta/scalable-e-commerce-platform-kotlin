package com.ecommerce.payment.infrastructure

import arrow.core.getOrElse
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
import com.ecommerce.platform.messaging.envelope.EventType
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.reactor.mono
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Old enough for the retry job (`payment.retry.delay` is 60 seconds). */
private val LONG_AGO: Instant = Instant.now().minus(Duration.ofMinutes(10))
private val QUIET: Duration = Duration.ofSeconds(2)
private val STORE_TIMEOUT: Duration = Duration.ofSeconds(10)

/**
 * The retry job of pending charges (US4 scenario 7, `payment.retry.*`, interval shortened by the test configuration):
 * a pending attempt older than the delay is voided and retried as attempt N + 1 with its event, up to the maximum of
 * attempts; an approval of an order cancelled meanwhile is refunded.
 */
class PaymentRetryIT : PaymentIntegrationTest() {
    @Autowired
    lateinit var ledger: PaymentLedger

    /** The first, pending attempt of [charge], created long ago, as an unreachable provider left it (not stored). */
    private fun firstAttempt(charge: Charge): PaymentAttempt {
        val request =
            ChargeRequest(
                OrderId(charge.orderId),
                AccountId(charge.owner),
                Money(charge.amountMinor, "BRL"),
                PaymentMethodRef.of(charge.token).getOrElse { error(it) },
                IdempotencyKey(charge.key),
            )
        return PaymentAttempt.charge(
            PaymentAttemptId(UUID.randomUUID()),
            request,
            ProviderDecision.Unreachable,
            LONG_AGO,
        )
    }

    /** The retry of [attempt], still pending, created long ago (not stored). */
    private fun retryOf(attempt: PaymentAttempt): PaymentAttempt =
        attempt.retry(PaymentAttemptId(UUID.randomUUID()), ProviderDecision.Unreachable, LONG_AGO)

    private fun pendingSinceLongAgo(charge: Charge): PaymentAttempt = store(firstAttempt(charge))

    private fun store(attempt: PaymentAttempt): PaymentAttempt {
        check(mono { ledger.attempts.insert(attempt) }.block(STORE_TIMEOUT) == true) { "attempt not stored" }
        return attempt
    }

    private fun awaitAttempts(
        charge: Charge,
        vararg outcomes: String,
    ) {
        await().atMost(EVENT_WAIT).untilAsserted { attemptsOf(charge) shouldContainExactly outcomes.toList() }
    }

    @Test
    fun `a pending tok_sim_unreachable charge is approved by its retry, published as PaymentApproved`() {
        val charge = Charge(token = UNREACHABLE_TOKEN)
        val first = pendingSinceLongAgo(charge)

        awaitAttempts(charge, "voided", "approved")

        val event =
            recorded.awaitType(EventType.PaymentApproved) {
                it.payload["orderId"].asString() ==
                    "${charge.orderId}"
            }
        val retryId = event.payload["paymentId"].asString()
        event.payload["idempotencyKey"].asString() shouldBe
            IdempotencyKey.retryOf(IdempotencyKey(charge.key)).toString()
        val page = body(read("$PAYMENTS/attempts?orderId=${charge.orderId}", charge.owner).expectStatus().isOk)
        val items = (page["items"] as List<*>).map { it as Map<*, *> }
        items.map { it["id"] } shouldContainExactly listOf(retryId, first.id.toString())
        items.map { it["outcome"] } shouldContainExactly listOf("approved", "voided")
        items.map { it["attemptNumber"] } shouldContainExactly listOf(2, 1)
        items.first()["retryOf"] shouldBe first.id.toString()
        items.last().containsKey("retryOf") shouldBe false
    }

    @Test
    fun `a tok_sim_unreachable_forever charge is retried while pending and never beyond the maximum of attempts`() {
        val charge = Charge(token = UNREACHABLE_FOREVER_TOKEN)
        pendingSinceLongAgo(charge)

        awaitAttempts(charge, "voided", "pending")
        recorded.awaitType(EventType.PaymentPending) { it.payload["orderId"].asString() == "${charge.orderId}" }

        val last = Charge(token = UNREACHABLE_FOREVER_TOKEN)
        val first = firstAttempt(last)
        val second = retryOf(first)
        val third = retryOf(second)
        listOf(first.void(), second.void(), third).forEach(::store)
        await().during(QUIET).atMost(QUIET.multipliedBy(2)).untilAsserted {
            attemptsOf(last) shouldContainExactly listOf("voided", "voided", "pending")
        }
    }

    @Test
    fun `a retry approved for an order cancelled meanwhile is refunded with RefundRecorded for the shopper`() {
        val charge = Charge(token = UNREACHABLE_TOKEN)
        column(
            "INSERT INTO cancelled_orders (order_id, account_id, email, phone, preferred_channels, recorded_at) " +
                "VALUES (:id, :owner, 'ada@example.test', NULL, ARRAY['email'], now()) RETURNING order_id",
            "id" to charge.orderId,
            "owner" to charge.owner,
        )
        pendingSinceLongAgo(charge)

        val refund =
            recorded.awaitType(EventType.RefundRecorded) {
                it.payload["orderId"].asString() ==
                    "${charge.orderId}"
            }

        refund.payload["recipient"]["email"].asString() shouldBe "ada@example.test"
        attemptsOf(charge) shouldContainExactly listOf("voided", "approved")
        column("SELECT count(*) FROM refunds WHERE order_id = :id", "id" to charge.orderId) shouldBe listOf(1L)
    }
}
