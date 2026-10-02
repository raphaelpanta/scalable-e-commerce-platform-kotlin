package com.ecommerce.payment.domain

import arrow.core.getOrElse
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.uuid
import java.time.Instant

const val BRL = "BRL"
const val APPROVE_TOKEN = "tok_sim_approve_4242"
val NOW: Instant = Instant.parse("2026-10-02T10:15:01Z")

/** One value of this generator, outside a property. */
fun <T> Arb<T>.one(): T = sample(RandomSource.default()).value

fun money(amountMinor: Long): Money = Money(amountMinor, BRL)

fun token(value: String): PaymentMethodRef = PaymentMethodRef.of(value).getOrElse { error(it) }

fun reference(value: String): ProviderReference = ProviderReference(value)

/** Generators of the payment domain's values. */
object PaymentArbs {
    private val tokenChars = ('a'..'z') + ('0'..'9') + '_'

    val orderId: Arb<OrderId> = Arb.uuid().map(::OrderId)
    val accountId: Arb<AccountId> = Arb.uuid().map(::AccountId)
    val attemptId: Arb<PaymentAttemptId> = Arb.uuid().map(::PaymentAttemptId)
    val refundId: Arb<RefundId> = Arb.uuid().map(::RefundId)
    val key: Arb<IdempotencyKey> = Arb.uuid().map(::IdempotencyKey)
    val amount: Arb<Money> = Arb.long(1L..10_000_000L).map(::money)
    val category: Arb<DeclineCategory> = Arb.enum<DeclineCategory>()

    /** Tokens that no token rule matches: neither the unreachable token nor the decline prefix. */
    val plainToken: Arb<PaymentMethodRef> =
        Arb
            .list(Arb.element(tokenChars), 1..40)
            .map { "tok_" + it.joinToString("") }
            .filter {
                it != SimulatedPaymentRules.UNREACHABLE_TOKEN &&
                    !it.startsWith(SimulatedPaymentRules.DECLINE_TOKEN_PREFIX)
            }.map(::token)

    /** Amounts whose minor units end in neither 13 nor 14. */
    val plainAmount: Arb<Money> =
        amount.filter {
            val digits = it.amountMinor.toString()
            !digits.endsWith("13") && !digits.endsWith("14")
        }

    val chargeRequest: Arb<ChargeRequest> =
        arbitrary {
            ChargeRequest(orderId.bind(), accountId.bind(), amount.bind(), plainToken.bind(), key.bind())
        }

    /** The decision of a provider: approved, declined with a category, or unreachable. */
    val decision: Arb<ProviderDecision> =
        arbitrary {
            when (Arb.enum<PaymentOutcome>().bind()) {
                PaymentOutcome.APPROVED -> {
                    ProviderDecision.Approved(
                        reference("sim_ch_" + Arb.long(0L..999_999L).bind()),
                    )
                }

                PaymentOutcome.DECLINED -> {
                    ProviderDecision.Declined(category.bind(), reference("sim_ch_declined"))
                }

                PaymentOutcome.PENDING -> {
                    ProviderDecision.Unreachable
                }
            }
        }

    val attempt: Arb<PaymentAttempt> =
        arbitrary { PaymentAttempt.charge(attemptId.bind(), chargeRequest.bind(), decision.bind(), NOW) }

    val approvedAttempt: Arb<PaymentAttempt> =
        arbitrary {
            PaymentAttempt.charge(
                attemptId.bind(),
                chargeRequest.bind(),
                ProviderDecision.Approved(reference("sim_ch_approved")),
                NOW,
            )
        }
}
