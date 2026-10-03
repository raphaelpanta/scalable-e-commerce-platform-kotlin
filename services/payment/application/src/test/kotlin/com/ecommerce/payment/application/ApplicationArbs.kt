package com.ecommerce.payment.application

import com.ecommerce.payment.domain.AccountId
import com.ecommerce.payment.domain.ChargeRequest
import com.ecommerce.payment.domain.DeclineCategory
import com.ecommerce.payment.domain.IdempotencyKey
import com.ecommerce.payment.domain.Money
import com.ecommerce.payment.domain.OrderId
import com.ecommerce.payment.domain.PaymentAttemptId
import com.ecommerce.payment.domain.PaymentMethodRef
import com.ecommerce.payment.domain.ProviderDecision
import com.ecommerce.payment.domain.ProviderReference
import com.ecommerce.payment.domain.RefundRequest
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.uuid

/**
 * Iterations of each application property: each runs the use cases against fresh in-memory fakes, and the mutation
 * tests run every property once per mutant, so the count stays moderate.
 */
const val PROPERTIES = 200

/** Generators of the payment application's inputs (Constitution V: property tests with generators). */
object ApplicationArbs {
    private val tokenChars = ('a'..'z') + ('0'..'9') + '_'

    val orderId: Arb<OrderId> = Arb.uuid().map(::OrderId)
    val accountId: Arb<AccountId> = Arb.uuid().map(::AccountId)
    val attemptId: Arb<PaymentAttemptId> = Arb.uuid().map(::PaymentAttemptId)
    val key: Arb<IdempotencyKey> = Arb.uuid().map(::IdempotencyKey)
    val amount: Arb<Money> = Arb.long(1L..10_000_000L).map(::brl)
    val paymentMethod: Arb<PaymentMethodRef> =
        Arb.list(Arb.element(tokenChars), 1..40).map { token("tok_" + it.joinToString("")) }

    val chargeRequest: Arb<ChargeRequest> =
        arbitrary { ChargeRequest(orderId.bind(), accountId.bind(), amount.bind(), paymentMethod.bind(), key.bind()) }

    /** What a provider may answer: approved, declined with any category, or unreachable. */
    val decision: Arb<ProviderDecision> =
        Arb.choice(
            Arb.long(0L..999_999L).map { ProviderDecision.Approved(ProviderReference("sim_ch_$it")) },
            Arb.enum<DeclineCategory>().map { ProviderDecision.Declined(it, ProviderReference("sim_ch_declined")) },
            arbitrary { ProviderDecision.Unreachable },
        )

    /** A change of the body of a charge request that keeps its key: another order, owner, amount or token. */
    val chargeDrift: Arb<(ChargeRequest) -> ChargeRequest> =
        Arb.choice(
            orderId.map { other -> { request: ChargeRequest -> request.copy(orderId = other) } },
            accountId.map { other -> { request: ChargeRequest -> request.copy(accountId = other) } },
            Arb.long(1L..1_000L).map { delta ->
                { request: ChargeRequest -> request.copy(amount = brl(request.amount.amountMinor + delta)) }
            },
            paymentMethod.map { suffix ->
                { request: ChargeRequest ->
                    request.copy(paymentMethodRef = token(request.paymentMethodRef.token + suffix.token))
                }
            },
        )

    /** A change of the body of a refund request that keeps its key: another order, attempt or amount. */
    val refundDrift: Arb<(RefundRequest) -> RefundRequest> =
        Arb.choice(
            orderId.map { other -> { request: RefundRequest -> request.copy(orderId = other) } },
            attemptId.map { other -> { request: RefundRequest -> request.copy(attemptId = other) } },
            Arb.long(1L..1_000L).map { delta ->
                { request: RefundRequest -> request.copy(amount = brl(request.amount.amountMinor + delta)) }
            },
        )
}
