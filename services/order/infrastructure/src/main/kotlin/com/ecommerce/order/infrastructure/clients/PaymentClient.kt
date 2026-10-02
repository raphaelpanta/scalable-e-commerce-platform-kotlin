package com.ecommerce.order.infrastructure.clients

import com.ecommerce.order.application.ChargeRequest
import com.ecommerce.order.application.PaymentPort
import com.ecommerce.order.domain.DeclineCategory
import com.ecommerce.order.domain.IdempotencyKey
import com.ecommerce.order.domain.Money
import com.ecommerce.order.domain.OrderId
import com.ecommerce.order.domain.PaymentAttemptId
import com.ecommerce.order.domain.PaymentOutcome
import com.ecommerce.platform.http.awaitBodyOrProblem
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientException
import java.util.UUID

/** `ChargeRequest` of payment-internal.yaml. */
data class ChargeJson(
    val orderId: UUID,
    val accountId: UUID,
    val amount: MoneyJson,
    val paymentMethodRef: String,
)

/** `ChargeAttempt` (the fields the order reads; `declineCategory` only when declined). */
data class ChargeAttemptJson(
    val attemptId: UUID,
    val outcome: String,
    val declineCategory: String? = null,
) {
    fun toOutcome(): PaymentOutcome =
        when (outcome) {
            APPROVED -> {
                PaymentOutcome.Approved(PaymentAttemptId(attemptId))
            }

            DECLINED -> {
                val category = declineCategory?.let(DeclineCategory::fromWire) ?: DeclineCategory.CARD_REJECTED
                PaymentOutcome.Declined(PaymentAttemptId(attemptId), category)
            }

            else -> {
                PaymentOutcome.Pending(PaymentAttemptId(attemptId))
            }
        }

    private companion object {
        const val APPROVED = "approved"
        const val DECLINED = "declined"
    }
}

/** `RefundRequest`. */
data class RefundJson(
    val orderId: UUID,
    val attemptId: UUID,
    val amount: MoneyJson,
)

/** `RefundRecord` (the fields the order reads). */
data class RefundRecordJson(
    val refundId: UUID,
    val orderId: UUID,
    val attemptId: UUID,
    val status: String,
)

/**
 * [PaymentPort] over payment-internal.yaml. The charge is keyed by the checkout `Idempotency-Key`, so this call and
 * the `OrderPlaced` consumer of payment converge on one attempt. A payment service that cannot be reached, or
 * answers with an error, leaves the payment pending: the order is placed and the events settle it later.
 */
class PaymentClient(
    private val client: WebClient,
) : PaymentPort {
    override suspend fun charge(request: ChargeRequest): PaymentOutcome =
        try {
            client
                .post()
                .uri("/internal/charges")
                .header(IDEMPOTENCY_KEY, request.idempotencyKey.toString())
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(
                    ChargeJson(
                        request.orderId.value,
                        request.accountId.value,
                        MoneyJson(request.amount.amountMinor, request.amount.currency),
                        request.paymentMethodRef,
                    ),
                ).awaitBodyOrProblem<ChargeAttemptJson>()
                .fold(
                    { failure ->
                        log.warn("The charge answered {}; the payment stays pending", failure.status)
                        PaymentOutcome.Pending(null)
                    },
                    { it.toOutcome() },
                )
        } catch (failure: WebClientException) {
            log.warn("The payment service could not be reached; the payment stays pending", failure)
            PaymentOutcome.Pending(null)
        }

    /**
     * Records a full refund of an approved charge (`POST /internal/refunds`, keyed by [key]); the id of the refund,
     * or null when it was refused. The cancellation flow relies on the `OrderCancelled` consumer of payment instead;
     * this call is the synchronous edge of the order -> payment pact.
     */
    suspend fun refund(
        orderId: OrderId,
        attemptId: PaymentAttemptId,
        amount: Money,
        key: IdempotencyKey,
    ): UUID? =
        required("payment") {
            client
                .post()
                .uri("/internal/refunds")
                .header(IDEMPOTENCY_KEY, key.toString())
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(RefundJson(orderId.value, attemptId.value, MoneyJson(amount.amountMinor, amount.currency)))
                .awaitBodyOrProblem<RefundRecordJson>()
        }.getOrNull()?.refundId

    private companion object {
        const val IDEMPOTENCY_KEY = "Idempotency-Key"
        val log: Logger = LoggerFactory.getLogger(PaymentClient::class.java)
    }
}
