package com.ecommerce.payment.infrastructure.web

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import arrow.core.toNonEmptyListOrNull
import com.ecommerce.payment.domain.AccountId
import com.ecommerce.payment.domain.Caller
import com.ecommerce.payment.domain.ChargeRequest
import com.ecommerce.payment.domain.IdempotencyKey
import com.ecommerce.payment.domain.Money
import com.ecommerce.payment.domain.OrderId
import com.ecommerce.payment.domain.PageRequest
import com.ecommerce.payment.domain.PaymentAttemptId
import com.ecommerce.payment.domain.PaymentMethodRef
import com.ecommerce.payment.domain.RefundId
import com.ecommerce.payment.domain.RefundRequest
import com.ecommerce.payment.domain.Role
import com.ecommerce.platform.core.problem.Problem
import com.ecommerce.platform.core.result.ValidationError
import com.ecommerce.platform.security.AccountPrincipal
import org.springframework.web.reactive.function.server.ServerRequest
import java.math.BigInteger
import java.util.UUID
import com.ecommerce.platform.security.Role as TokenRole

private const val BAD_REQUEST = 400
private val CHARGE_FIELDS = setOf("orderId", "accountId", "amount", "paymentMethodRef")
private val REFUND_FIELDS = setOf("orderId", "attemptId", "amount")
private val MONEY_FIELDS = setOf("amountMinor", "currency")
private val CURRENCY = Regex("[A-Z]{3}")

/** The use-case caller of an authenticated request. */
fun AccountPrincipal.toCaller(): Caller =
    Caller(
        AccountId(accountId),
        roles
            .map {
                when (it) {
                    TokenRole.SHOPPER -> Role.SHOPPER
                    TokenRole.OPERATOR -> Role.OPERATOR
                }
            }.toSet(),
    )

/**
 * Parsing and schema checks of the payment.yaml and payment-internal.yaml requests: a malformed request, or a field
 * that violates its documented constraint, is a 400 `validation` problem naming every broken field.
 */
object PaymentRequests {
    /** `Idempotency-Key`: a mandatory UUID. */
    fun idempotencyKey(request: ServerRequest): Either<Problem, IdempotencyKey> =
        uuidOf(request.headers().firstHeader("Idempotency-Key"))?.let(::IdempotencyKey)?.right()
            ?: badRequest(listOf(ValidationError("Idempotency-Key", "is required and must be a UUID"))).left()

    /** `ChargeRequest`: `orderId`, `accountId`, a positive `amount` and a 1..128 character `paymentMethodRef`. */
    fun charge(
        body: Map<String, Any?>?,
        key: IdempotencyKey,
    ): Either<Problem, ChargeRequest> {
        val fields = body.orEmpty()
        val orderId = uuidOf(fields["orderId"])
        val accountId = uuidOf(fields["accountId"])
        val amount = moneyOf(fields["amount"])
        val token = (fields["paymentMethodRef"] as? String)?.let { PaymentMethodRef.of(it).getOrNull() }
        val errors =
            required(body) + unknownFields(fields.keys, CHARGE_FIELDS) + amountErrors(fields["amount"]) +
                listOfNotNull(
                    ValidationError("orderId", "must be a UUID").takeIf { orderId == null },
                    ValidationError("accountId", "must be a UUID").takeIf { accountId == null },
                    ValidationError("paymentMethodRef", "must be 1 to 128 characters").takeIf { token == null },
                )
        // No error means every member parsed.
        return if (errors.isEmpty()) {
            ChargeRequest(
                OrderId(checkNotNull(orderId)),
                AccountId(checkNotNull(accountId)),
                checkNotNull(amount),
                checkNotNull(token),
                key,
            ).right()
        } else {
            badRequest(errors).left()
        }
    }

    /** `RefundRequest`: `orderId`, `attemptId` and a positive `amount`. */
    fun refund(
        body: Map<String, Any?>?,
        key: IdempotencyKey,
    ): Either<Problem, RefundRequest> {
        val fields = body.orEmpty()
        val orderId = uuidOf(fields["orderId"])
        val attemptId = uuidOf(fields["attemptId"])
        val amount = moneyOf(fields["amount"])
        val errors =
            required(body) + unknownFields(fields.keys, REFUND_FIELDS) + amountErrors(fields["amount"]) +
                listOfNotNull(
                    ValidationError("orderId", "must be a UUID").takeIf { orderId == null },
                    ValidationError("attemptId", "must be a UUID").takeIf { attemptId == null },
                )
        return if (errors.isEmpty()) {
            RefundRequest(
                OrderId(checkNotNull(orderId)),
                PaymentAttemptId(checkNotNull(attemptId)),
                checkNotNull(amount),
                key,
            ).right()
        } else {
            badRequest(errors).left()
        }
    }

    /** The `{attemptId}` path variable. */
    fun attemptId(request: ServerRequest): Either<Problem, PaymentAttemptId> =
        uuidParameter(request.pathVariable("attemptId"), "attemptId").map(::PaymentAttemptId)

    /** The `{refundId}` path variable. */
    fun refundId(request: ServerRequest): Either<Problem, RefundId> =
        uuidParameter(request.pathVariable("refundId"), "refundId").map(::RefundId)

    /** The mandatory `orderId` query parameter. */
    fun orderId(request: ServerRequest): Either<Problem, OrderId> =
        uuidParameter(request.queryParam("orderId").orElse(null), "orderId").map(::OrderId)

    /** `page` (default 0) and `size` (default 20). */
    fun page(request: ServerRequest): Either<Problem, PageRequest> {
        val page = request.queryParam("page").orElse("0").toIntOrNull()
        val size = request.queryParam("size").orElse(PageRequest.DEFAULT_SIZE.toString()).toIntOrNull()
        return when {
            page == null -> badRequest(listOf(ValidationError("page", "must be an integer"))).left()
            size == null -> badRequest(listOf(ValidationError("size", "must be an integer"))).left()
            else -> PageRequest.of(page, size).mapLeft { badRequest(listOf(ValidationError(it.field, it.reason))) }
        }
    }
}

private fun required(body: Map<String, Any?>?): List<ValidationError> =
    if (body == null) listOf(ValidationError("body", "is required")) else emptyList()

private fun amountErrors(value: Any?): List<ValidationError> {
    val amount = value as? Map<*, *> ?: return listOf(ValidationError("amount", "is required"))
    return unknownFields(amount.keys, MONEY_FIELDS, "amount.") +
        listOfNotNull(
            ValidationError("amount.amountMinor", "must be an integer of at least 1").takeIf {
                minorOf(amount["amountMinor"]) == null
            },
            ValidationError("amount.currency", "must be an ISO-4217 code").takeIf {
                (amount["currency"] as? String)?.matches(CURRENCY) != true
            },
        )
}

private fun moneyOf(value: Any?): Money? {
    val amount = value as? Map<*, *>
    val minor = minorOf(amount?.get("amountMinor"))
    val currency = amount?.get("currency") as? String
    return if (minor != null && currency != null) Money.positive(minor, currency).getOrNull() else null
}

private fun minorOf(value: Any?): Long? =
    when (value) {
        is Int -> value.toLong()
        is Long -> value
        is BigInteger -> value.takeIf { it.bitLength() < Long.SIZE_BITS }?.toLong()
        else -> null
    }?.takeIf { it >= 1 }

private fun unknownFields(
    names: Set<*>,
    allowed: Set<String>,
    prefix: String = "",
): List<ValidationError> = names.filterNot { it in allowed }.map { ValidationError("$prefix$it", "is not allowed") }

private fun uuidParameter(
    value: String?,
    name: String,
): Either<Problem, UUID> {
    val uuid = uuidOf(value)
    return uuid?.right() ?: badRequest(listOf(ValidationError(name, "must be a UUID"))).left()
}

private fun uuidOf(value: Any?): UUID? = (value as? String)?.let { runCatching { UUID.fromString(it) }.getOrNull() }

private fun badRequest(errors: List<ValidationError>): Problem =
    errors.toNonEmptyListOrNull()?.let { Problem.validation(it, status = BAD_REQUEST) }
        ?: Problem.badRequest("The request is not valid.")
