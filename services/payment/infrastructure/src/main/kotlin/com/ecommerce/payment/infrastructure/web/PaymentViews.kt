package com.ecommerce.payment.infrastructure.web

import com.ecommerce.payment.domain.Money
import com.ecommerce.payment.domain.Page
import com.ecommerce.payment.domain.PaymentAttempt
import com.ecommerce.payment.domain.PaymentOutcome
import com.ecommerce.payment.domain.RefundRecord
import com.ecommerce.payment.domain.SimulatorRule
import com.ecommerce.payment.domain.SimulatorRules

/**
 * The JSON bodies of payment.yaml and payment-internal.yaml. Optional members are left out when absent (never
 * written as null), timestamps are ISO-8601 UTC with `Z`, money is `{amountMinor, currency}`.
 */
object PaymentViews {
    /** `ChargeAttempt` of payment-internal.yaml. */
    fun chargeAttempt(attempt: PaymentAttempt): Map<String, Any> =
        buildMap {
            put("attemptId", attempt.id.toString())
            put("orderId", attempt.orderId.toString())
            put("kind", attempt.kind.wire)
            put("outcome", attempt.outcome.wire)
            attempt.declineCategory?.let { put("declineCategory", it.wire) }
            attempt.providerReference?.let { put("providerReference", it.value) }
            put("createdAt", attempt.createdAt.toString())
        }

    /** `RefundRecord` of payment-internal.yaml. */
    fun refundRecord(refund: RefundRecord): Map<String, Any> =
        mapOf(
            "refundId" to refund.id.toString(),
            "orderId" to refund.orderId.toString(),
            "attemptId" to refund.attemptId.toString(),
            "amount" to money(refund.amount),
            "status" to refund.status.wire,
            "createdAt" to refund.createdAt.toString(),
        )

    /** `PaymentAttempt` of payment.yaml. */
    fun paymentAttempt(attempt: PaymentAttempt): Map<String, Any> =
        buildMap {
            put("id", attempt.id.toString())
            put("orderId", attempt.orderId.toString())
            put("amount", money(attempt.amount))
            put("outcome", attempt.outcome.wire)
            attempt.declineCategory?.let { put("declineReason", it.wire) }
            attempt.providerReference?.let { put("providerReference", it.value) }
            put("idempotencyKey", attempt.idempotencyKey.toString())
            put("createdAt", attempt.createdAt.toString())
        }

    /** `Refund` of payment.yaml: a recorded refund is an approved refund attempt of the simulated provider. */
    fun refund(refund: RefundRecord): Map<String, Any> =
        mapOf(
            "id" to refund.id.toString(),
            "orderId" to refund.orderId.toString(),
            "refundOf" to refund.attemptId.toString(),
            "amount" to money(refund.amount),
            "outcome" to PaymentOutcome.APPROVED.wire,
            "providerReference" to refund.providerReference.value,
            "idempotencyKey" to refund.idempotencyKey.toString(),
            "createdAt" to refund.createdAt.toString(),
        )

    /** `PaymentAttemptPage` or `RefundPage`. */
    fun <T> page(
        page: Page<T>,
        item: (T) -> Map<String, Any>,
    ): Map<String, Any> =
        mapOf(
            "items" to page.items.map(item),
            "page" to page.request.page,
            "size" to page.request.size,
            "totalItems" to page.totalItems,
        )

    /** `SimulatorRules` of payment.yaml. */
    fun simulatorRules(rules: SimulatorRules): Map<String, Any> =
        mapOf(
            "version" to rules.version,
            "defaultOutcome" to rules.defaultOutcome.wire,
            "rules" to rules.rules.sortedBy { it.order }.map(::simulatorRule),
        )

    private fun simulatorRule(rule: SimulatorRule): Map<String, Any> =
        buildMap {
            put("order", rule.order)
            put("id", rule.id)
            put("description", rule.description)
            put(
                "match",
                mapOf(
                    "field" to rule.match.field.wire,
                    "operator" to rule.match.operator.wire,
                    "value" to rule.match.value,
                ),
            )
            put("outcome", rule.outcome.wire)
            rule.declineCategory?.let { put("declineReason", it.wire) }
        }

    private fun money(amount: Money): Map<String, Any> =
        mapOf("amountMinor" to amount.amountMinor, "currency" to amount.currency)
}
