package com.ecommerce.notification.domain

import arrow.core.getOrElse
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.subsequence
import io.kotest.property.arbitrary.uuid
import java.time.Instant

/** One value of this generator, outside a property (fixtures of example-based tests). */
fun <T> Arb<T>.one(): T = sample(RandomSource.default()).value

/** Generators of the notification domain's values. */
object DomainArbs {
    val instant: Arb<Instant> = Arb.long(1_700_000_000L, 1_900_000_000L).map { Instant.ofEpochSecond(it) }

    val eventId: Arb<EventId> = Arb.uuid().map(::EventId)

    val accountId: Arb<AccountId> = Arb.uuid().map(::AccountId)

    val orderId: Arb<OrderId> = Arb.uuid().map(::OrderId)

    private val lower = ('a'..'z').toList()
    private val digits = ('0'..'9').toList()

    /** A string of [range] characters drawn from [chars]. */
    fun text(
        chars: List<Char>,
        range: IntRange,
    ): Arb<String> = Arb.list(Arb.element(chars), range).map { it.joinToString("") }

    val emailText: Arb<String> =
        arbitrary {
            val local = text(lower + digits, 1..12).bind()
            "$local@" + text(lower, 1..8).bind() + "." + Arb.element("test", "com").bind()
        }

    val email: Arb<EmailAddress> = emailText.map { raw -> EmailAddress.of(raw).getOrElse { error(it) } }

    val phoneText: Arb<String> =
        arbitrary { "+" + Arb.element(('1'..'9').toList()).bind() + text(digits, 7..14).bind() }

    val phone: Arb<PhoneNumber> = phoneText.map { raw -> PhoneNumber.of(raw).getOrElse { error(it) } }

    // Every subset of the two channels, empty to both. Arb.set(Arb.enum(), 0..2) had to draw both values
    // within a bounded number of tries and failed now and then ("the target size requirement of 2 could not
    // be satisfied").
    val channels: Arb<Set<NotificationChannel>> = Arb.subsequence(NotificationChannel.entries).map { it.toSet() }

    val contact: Arb<RecipientContact> =
        arbitrary {
            RecipientContact(
                email = email.orNull(0.1).bind(),
                phone = phone.orNull(0.3).bind(),
                phoneVerified = Arb.boolean().bind(),
                channels = channels.bind(),
                anonymised = Arb.boolean().bind(),
            )
        }

    /** Tokens as identity issues them: url-safe, 16 to 64 characters. */
    val token: Arb<String> = text(lower + ('A'..'Z') + digits + listOf('_', '-'), 16..64)

    val amount: Arb<Amount> = Arb.long(0L, 10_000_000L).map { Amount(it, "BRL") }

    val orderReference: Arb<OrderReference> =
        arbitrary {
            val sequence =
                Arb
                    .int(1, 9999)
                    .bind()
                    .toString()
                    .padStart(4, '0')
            OrderReference(orderId.bind(), "ORD-20261002-$sequence")
        }

    val address: Arb<AddressSummary> =
        arbitrary {
            AddressSummary(
                recipientName = "Ada Lovelace",
                line1 = "Rua " + text(lower, 1..20).bind(),
                line2 = Arb.element("Flat 2", null).bind(),
                city = "London",
                postalCode = "N1 9GU",
                country = "GB",
            )
        }

    val templateData: Arb<TemplateData> =
        arbitrary {
            val order = orderReference.bind()
            val total = amount.bind()
            when (Arb.enum<NotificationKind>().bind()) {
                NotificationKind.ACCOUNT_VERIFICATION -> {
                    TemplateData.AccountVerification(SecretLink.of("http://localhost:8080", "/verify", token.bind()))
                }

                NotificationKind.PASSWORD_RESET -> {
                    TemplateData.PasswordReset(SecretLink.of("http://localhost:8080", "/reset-password", token.bind()))
                }

                NotificationKind.ORDER_CONFIRMATION -> {
                    val lines = Arb.list(amount.map { price -> LineSummary("Puzzle", 2, price) }, 1..3).bind()
                    TemplateData.OrderConfirmation(order, lines, total, address.bind())
                }

                NotificationKind.PAYMENT_FAILURE -> {
                    TemplateData.PaymentFailure(order, total, "insufficient_funds")
                }

                NotificationKind.ORDER_SHIPPED -> {
                    TemplateData.OrderShipped(order)
                }

                NotificationKind.ORDER_DELIVERED -> {
                    TemplateData.OrderDelivered(order)
                }

                NotificationKind.ORDER_CANCELLED -> {
                    TemplateData.OrderCancelled(order, total, "SHOPPER_REQUEST", Arb.boolean().bind())
                }

                NotificationKind.REFUND_CONFIRMATION -> {
                    TemplateData.RefundConfirmation(order.orderId, total)
                }
            }
        }

    val failure: Arb<DeliveryFailure> = Arb.enum<FailureCategory>().map { DeliveryFailure(it, "SMTP refused (451)") }

    /** Failures worth retrying. */
    val transientFailure: Arb<DeliveryFailure> =
        Arb
            .element(FailureCategory.CHANNEL_UNAVAILABLE, FailureCategory.REJECTED_BY_PROVIDER)
            .map { DeliveryFailure(it, "SMTP refused (451)") }

    /** A notification in [status] with [attempts] attempts so far. */
    fun notification(
        status: DeliveryStatus = DeliveryStatus.QUEUED,
        attempts: Int = 0,
    ): Arb<Notification> =
        arbitrary {
            Notification(
                id = NotificationId.random(),
                sourceEventId = eventId.bind(),
                kind = Arb.enum<NotificationKind>().bind(),
                channel = Arb.enum<NotificationChannel>().bind(),
                accountId = accountId.bind(),
                recipient = RecipientAddress("ada@example.test"),
                content = MessageContent("Subject", "Body"),
                orderId = orderId.orNull().bind(),
                correlationId = "3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13",
                status = status,
                attempts = attempts,
                createdAt = instant.bind(),
            )
        }
}
