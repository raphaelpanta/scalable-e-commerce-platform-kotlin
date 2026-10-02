package com.ecommerce.payment.infrastructure.web

import arrow.core.raise.either
import com.ecommerce.payment.application.AuthoriseCharge
import com.ecommerce.payment.application.GetPaymentAttempt
import com.ecommerce.payment.application.GetRefund
import com.ecommerce.payment.application.GetSimulatorRules
import com.ecommerce.payment.application.ListPaymentAttemptsForOrder
import com.ecommerce.payment.application.ListRefundsForOrder
import com.ecommerce.payment.application.RecordRefund
import com.ecommerce.payment.application.Recorded
import com.ecommerce.platform.problem.toServerResponse
import com.ecommerce.platform.security.requireAccount
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.awaitBodyOrNull
import org.springframework.web.reactive.function.server.bodyValueAndAwait

/**
 * `createCharge` and `createRefund` of payment-internal.yaml (order service only; platform-core's
 * `InternalTokenWebFilter` answers 401 without a valid `X-Internal-Token` before these run): 201 when the record is
 * created, 200 for a replay of the same key and body, 422 / 409 / 404 problems otherwise.
 */
class InternalPaymentHandlers(
    private val authoriseCharge: AuthoriseCharge,
    private val recordRefund: RecordRefund,
) {
    /** `POST /internal/charges`. */
    suspend fun createCharge(request: ServerRequest): ServerResponse =
        either {
            val key = PaymentRequests.idempotencyKey(request).bind()
            val charge = PaymentRequests.charge(request.awaitBodyOrNull<Map<String, Any?>>(), key).bind()
            authoriseCharge(charge).mapLeft(PaymentProblems::of).bind()
        }.toServerResponse(request) { answer(it) { attempt -> PaymentViews.chargeAttempt(attempt) } }

    /** `POST /internal/refunds`. */
    suspend fun createRefund(request: ServerRequest): ServerResponse =
        either {
            val key = PaymentRequests.idempotencyKey(request).bind()
            val refund = PaymentRequests.refund(request.awaitBodyOrNull<Map<String, Any?>>(), key).bind()
            recordRefund(refund).mapLeft(PaymentProblems::of).bind()
        }.toServerResponse(request) { answer(it) { record -> PaymentViews.refundRecord(record) } }

    private suspend fun <T> answer(
        recorded: Recorded<T>,
        view: (T) -> Any,
    ): ServerResponse =
        ServerResponse
            .status(if (recorded.created) HttpStatus.CREATED else HttpStatus.OK)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValueAndAwait(view(recorded.value))
}

/**
 * The public reads of payment.yaml under `/api/v1/payments` (bearer token required): operators see every payment,
 * shoppers the payments of their own orders (others 404); the simulator rules are for operators (shoppers 403).
 */
class PaymentQueryHandlers(
    private val getPaymentAttempt: GetPaymentAttempt,
    private val listPaymentAttempts: ListPaymentAttemptsForOrder,
    private val listRefunds: ListRefundsForOrder,
    private val getRefund: GetRefund,
    private val getSimulatorRules: GetSimulatorRules,
) {
    /** `GET /api/v1/payments/attempts/{attemptId}`. */
    suspend fun getPaymentAttempt(request: ServerRequest): ServerResponse =
        either {
            val caller = requireAccount().bind().toCaller()
            val id = PaymentRequests.attemptId(request).bind()
            getPaymentAttempt(caller, id).mapLeft(PaymentProblems::of).bind()
        }.toServerResponse(request) { ok(PaymentViews.paymentAttempt(it)) }

    /** `GET /api/v1/payments/attempts?orderId=`: newest first. */
    suspend fun listPaymentAttemptsForOrder(request: ServerRequest): ServerResponse =
        either {
            val caller = requireAccount().bind().toCaller()
            val orderId = PaymentRequests.orderId(request).bind()
            val page = PaymentRequests.page(request).bind()
            listPaymentAttempts(caller, orderId, page).mapLeft(PaymentProblems::of).bind()
        }.toServerResponse(request) { ok(PaymentViews.page(it, PaymentViews::paymentAttempt)) }

    /** `GET /api/v1/payments/refunds?orderId=`. */
    suspend fun listRefundsForOrder(request: ServerRequest): ServerResponse =
        either {
            val caller = requireAccount().bind().toCaller()
            val orderId = PaymentRequests.orderId(request).bind()
            val page = PaymentRequests.page(request).bind()
            listRefunds(caller, orderId, page).mapLeft(PaymentProblems::of).bind()
        }.toServerResponse(request) { ok(PaymentViews.page(it, PaymentViews::refund)) }

    /** `GET /api/v1/payments/refunds/{refundId}`. */
    suspend fun getRefund(request: ServerRequest): ServerResponse =
        either {
            val caller = requireAccount().bind().toCaller()
            val id = PaymentRequests.refundId(request).bind()
            getRefund(caller, id).mapLeft(PaymentProblems::of).bind()
        }.toServerResponse(request) { ok(PaymentViews.refund(it)) }

    /** `GET /api/v1/payments/simulator/rules` (operators). */
    suspend fun getSimulatorRules(request: ServerRequest): ServerResponse =
        either {
            val caller = requireAccount().bind().toCaller()
            getSimulatorRules(caller).mapLeft(PaymentProblems::of).bind()
        }.toServerResponse(request) { ok(PaymentViews.simulatorRules(it)) }

    private suspend fun ok(body: Any): ServerResponse =
        ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValueAndAwait(body)
}
