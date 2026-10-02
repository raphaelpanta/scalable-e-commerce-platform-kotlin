package com.ecommerce.payment.infrastructure.persistence

import com.ecommerce.payment.domain.AccountId
import com.ecommerce.payment.domain.DeclineCategory
import com.ecommerce.payment.domain.IdempotencyKey
import com.ecommerce.payment.domain.Money
import com.ecommerce.payment.domain.OrderId
import com.ecommerce.payment.domain.PaymentAttempt
import com.ecommerce.payment.domain.PaymentAttemptId
import com.ecommerce.payment.domain.PaymentMethodRef
import com.ecommerce.payment.domain.PaymentOutcome
import com.ecommerce.payment.domain.ProviderReference
import com.ecommerce.payment.domain.RefundId
import com.ecommerce.payment.domain.RefundRecord
import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec
import java.time.Instant
import java.util.UUID

/** Binds [value], or a typed NULL when it is absent. */
internal fun GenericExecuteSpec.bindNullable(
    name: String,
    value: Any?,
    type: Class<*>,
): GenericExecuteSpec = if (value == null) bindNull(name, type) else bind(name, value)

/** Runs a write and answers how many rows it changed. */
internal suspend fun GenericExecuteSpec.awaitRowsUpdated(): Long = fetch().rowsUpdated().awaitSingle()

private fun <T : Any> Readable.required(
    name: String,
    type: Class<T>,
): T = checkNotNull(get(name, type)) { "column $name is null" }

/** A `payment_attempts` row as an attempt. */
internal fun Readable.toAttempt(): PaymentAttempt =
    PaymentAttempt(
        id = PaymentAttemptId(required("id", UUID::class.java)),
        orderId = OrderId(required("order_id", UUID::class.java)),
        accountId = AccountId(required("account_id", UUID::class.java)),
        amount = money(),
        paymentMethodRef =
            PaymentMethodRef.of(required("payment_method_ref", String::class.java)).fold({ error(it) }, { it }),
        outcome = checkNotNull(PaymentOutcome.fromWire(required("outcome", String::class.java))),
        declineCategory =
            get(
                "decline_category",
                String::class.java,
            )?.let { checkNotNull(DeclineCategory.fromWire(it)) },
        providerReference = get("provider_reference", String::class.java)?.let(::ProviderReference),
        idempotencyKey = IdempotencyKey(required("idempotency_key", UUID::class.java)),
        createdAt = required("created_at", Instant::class.java),
    )

/** A `refunds` row as a refund. */
internal fun Readable.toRefund(): RefundRecord =
    RefundRecord(
        id = RefundId(required("id", UUID::class.java)),
        orderId = OrderId(required("order_id", UUID::class.java)),
        accountId = AccountId(required("account_id", UUID::class.java)),
        attemptId = PaymentAttemptId(required("attempt_id", UUID::class.java)),
        amount = money(),
        providerReference = ProviderReference(required("provider_reference", String::class.java)),
        idempotencyKey = IdempotencyKey(required("idempotency_key", UUID::class.java)),
        createdAt = required("created_at", Instant::class.java),
        announcedAt = get("announced_at", Instant::class.java),
    )

private fun Readable.money(): Money =
    Money(required("amount_minor", Long::class.javaObjectType), required("currency", String::class.java).trim())

/** The total of a `count(*)` row. */
internal fun Readable.count(): Long = required("total", Long::class.javaObjectType)
