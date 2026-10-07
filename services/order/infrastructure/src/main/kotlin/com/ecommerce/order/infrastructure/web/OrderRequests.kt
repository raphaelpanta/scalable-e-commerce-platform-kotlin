package com.ecommerce.order.infrastructure.web

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import arrow.core.toNonEmptyListOrNull
import com.ecommerce.order.domain.AccountId
import com.ecommerce.order.domain.AddressId
import com.ecommerce.order.domain.Caller
import com.ecommerce.order.domain.CheckoutRequest
import com.ecommerce.order.domain.IdempotencyKey
import com.ecommerce.order.domain.OrderId
import com.ecommerce.order.domain.OrderStatus
import com.ecommerce.order.domain.PageRequest
import com.ecommerce.order.domain.Role
import com.ecommerce.platform.core.problem.Problem
import com.ecommerce.platform.core.result.ValidationError
import com.ecommerce.platform.security.AccountPrincipal
import org.springframework.web.reactive.function.server.ServerRequest
import java.util.UUID
import com.ecommerce.platform.security.Role as TokenRole

private const val BAD_REQUEST = 400
private const val MAX_TOKEN_LENGTH = 128
private const val MAX_REVISION_LENGTH = 128
private val PLACE_ORDER_FIELDS = setOf("addressId", "cartRevision", "paymentMethod")
private val PAYMENT_METHOD_FIELDS = setOf("type", "token")
private val TRANSITION_FIELDS = setOf("orderStatus")
private const val CARD = "card"

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

/** Parsing and schema checks of the order.yaml requests; malformed input is a 400 `validation` problem. */
object OrderRequests {
    /** `Idempotency-Key`: mandatory UUID. */
    fun idempotencyKey(request: ServerRequest): Either<Problem, IdempotencyKey> =
        uuidOf(request.headers().firstHeader("Idempotency-Key"))?.let(::IdempotencyKey)?.right()
            ?: Problem.badRequest("The Idempotency-Key header is required and must be a UUID.").left()

    /** The `{orderId}` path variable. */
    fun orderId(request: ServerRequest): Either<Problem, OrderId> =
        uuidOf(request.pathVariable("orderId"))?.let(::OrderId)?.right() ?: invalid("orderId", "must be a UUID")

    /** `page` (default 0) and `size` (default 20). */
    fun page(request: ServerRequest): Either<Problem, PageRequest> {
        val page = request.queryParam("page").orElse("0").toIntOrNull()
        val size = request.queryParam("size").orElse(PageRequest.DEFAULT_SIZE.toString()).toIntOrNull()
        return when {
            page == null -> invalid("page", "must be an integer")
            size == null -> invalid("size", "must be an integer")
            else -> PageRequest.of(page, size).mapLeft { badRequest(listOf(ValidationError(it.field, it.reason))) }
        }
    }

    /** The optional `orderStatus` filter of the order list: absent is no filter, an unknown value a 400. */
    fun orderStatus(request: ServerRequest): Either<Problem, OrderStatus?> =
        request.queryParam("orderStatus").orElse(null)?.let { wire ->
            OrderStatus.fromWire(wire)?.right() ?: invalid("orderStatus", "must be an order status")
        } ?: null.right()

    /** `PlaceOrderRequest`: `addressId`, `cartRevision` and a card `paymentMethod`, nothing else. */
    fun checkout(body: Map<String, Any?>?): Either<Problem, CheckoutRequest> {
        if (body == null) return Problem.badRequest("A request body is required.").left()
        val method = body["paymentMethod"] as? Map<*, *>
        val addressId = uuidOf(body["addressId"])
        val revision = (body["cartRevision"] as? String)?.takeIf { it.isNotBlank() && it.length <= MAX_REVISION_LENGTH }
        val token = (method?.get("token") as? String)?.takeIf { it.isNotEmpty() && it.length <= MAX_TOKEN_LENGTH }
        val errors =
            unknownFields(body.keys, PLACE_ORDER_FIELDS) +
                unknownFields(method?.keys.orEmpty(), PAYMENT_METHOD_FIELDS, "paymentMethod.") +
                listOfNotNull(
                    ValidationError("addressId", "must be a UUID").takeIf { addressId == null },
                    ValidationError("cartRevision", "must be 1 to 128 characters").takeIf { revision == null },
                    ValidationError("paymentMethod", "is required").takeIf { method == null },
                    ValidationError("paymentMethod.type", "must be card").takeIf {
                        method != null &&
                            method["type"] != CARD
                    },
                    ValidationError("paymentMethod.token", "must be 1 to 128 characters").takeIf {
                        method != null &&
                            token == null
                    },
                )
        val valid =
            if (addressId != null && revision != null && token != null) {
                CheckoutRequest(AddressId(addressId), revision, CARD, token)
            } else {
                null
            }
        return if (errors.isEmpty() && valid != null) {
            valid.right()
        } else {
            badRequest(errors).left()
        }
    }

    /** `TransitionRequest`: one `orderStatus` value, nothing else. */
    fun transition(body: Map<String, Any?>?): Either<Problem, OrderStatus> {
        val status = (body?.get("orderStatus") as? String)?.let(OrderStatus::fromWire)
        val errors =
            unknownFields(body?.keys.orEmpty(), TRANSITION_FIELDS) +
                listOfNotNull(ValidationError("orderStatus", "must be an order status").takeIf { status == null })
        return if (errors.isEmpty() && status != null) status.right() else badRequest(errors).left()
    }

    private fun unknownFields(
        names: Set<*>,
        allowed: Set<String>,
        prefix: String = "",
    ): List<ValidationError> = names.filterNot { it in allowed }.map { ValidationError("$prefix$it", "is not allowed") }

    private fun uuidOf(value: Any?): UUID? = (value as? String)?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private fun <T> invalid(
        field: String,
        reason: String,
    ): Either<Problem, T> = badRequest(listOf(ValidationError(field, reason))).left()

    private fun badRequest(errors: List<ValidationError>): Problem =
        errors.toNonEmptyListOrNull()?.let { Problem.validation(it, status = BAD_REQUEST) }
            ?: Problem.badRequest("The request is not valid.")
}
