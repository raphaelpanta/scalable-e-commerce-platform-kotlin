package com.ecommerce.order.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class ValuesSpec :
    FunSpec({
        context("money") {
            test("valid amounts and currencies are accepted, others are refused with the field name") {
                checkAll(Arb.long(0L..Long.MAX_VALUE)) { amount ->
                    Money.of(amount, "BRL").getOrNull() shouldBe Money(amount, "BRL")
                }
                checkAll(Arb.long(Long.MIN_VALUE..-1L)) { amount ->
                    Money.of(amount, "BRL", "price").leftOrNull() shouldBe
                        OrderError.Invalid("price", "must not be negative")
                    shouldThrow<IllegalArgumentException> { Money(amount, "BRL") }
                }
                listOf("brl", "BR", "BRLX", "").forEach { currency ->
                    Money.of(1, currency).leftOrNull() shouldBe
                        OrderError.Invalid("amount", "currency must be an ISO-4217 code")
                    shouldThrow<IllegalArgumentException> { Money(1, currency) }
                }
                Money.zero("USD") shouldBe Money(0, "USD")
            }

            test("sums and multiples stay exact, refuse mixed currencies and overflow") {
                checkAll(Arb.long(0L..1_000_000_000L), Arb.long(0L..1_000_000_000L), Arb.int(1..99)) { a, b, q ->
                    (Money(a, "BRL") + Money(b, "BRL")) shouldBe Money(a + b, "BRL")
                    (Money(a, "BRL") * Quantity(q)) shouldBe Money(a * q, "BRL")
                }
                shouldThrow<IllegalArgumentException> { Money(1, "BRL") + Money(1, "USD") }
                shouldThrow<ArithmeticException> { Money(Long.MAX_VALUE, "BRL") + Money(1, "BRL") }
                shouldThrow<ArithmeticException> { Money(Long.MAX_VALUE, "BRL") * Quantity(2) }
            }
        }

        test("a quantity is between 1 and 99") {
            checkAll(Arb.int(-1000..1000)) { value ->
                val valid = value in 1..99
                Quantity.of(value).isRight() shouldBe valid
                if (valid) {
                    Quantity.of(value).getOrNull()?.value shouldBe value
                } else {
                    Quantity.of(value).leftOrNull() shouldBe OrderError.Invalid("quantity", "must be between 1 and 99")
                    shouldThrow<IllegalArgumentException> { Quantity(value) }
                }
            }
        }

        test("order numbers are ORD-<yyyyMMdd>-<sequence> with at least four digits") {
            OrderNumber.of(LocalDate.of(2026, 10, 2), 1).value shouldBe "ORD-20261002-0001"
            OrderNumber.of(LocalDate.of(2026, 1, 9), 12345).value shouldBe "ORD-20260109-12345"
            OrderNumber.of(LocalDate.of(2026, 1, 9), 12).toString() shouldBe "ORD-20260109-0012"
            shouldThrow<IllegalArgumentException> { OrderNumber.of(LocalDate.of(2026, 1, 9), 0) }
            shouldThrow<IllegalArgumentException> { OrderNumber("ORD-2026-1") }
        }

        test("paging accepts page >= 0 and size 1..100") {
            checkAll(Arb.int(-5..50), Arb.int(-5..120)) { page, size ->
                val result = PageRequest.of(page, size)
                when {
                    page < 0 -> {
                        result.leftOrNull() shouldBe OrderError.Invalid("page", "must be 0 or more")
                    }

                    size !in 1..100 -> {
                        result.leftOrNull() shouldBe
                            OrderError.Invalid("size", "must be between 1 and 100")
                    }

                    else -> {
                        result.getOrNull()?.offset shouldBe page.toLong() * size
                    }
                }
            }
        }

        test("contract values round-trip through their wire names") {
            checkAll(
                Arb.enum<OrderStatus>(),
                Arb.enum<PaymentStatus>(),
                Arb.enum<DeclineCategory>(),
                Arb.enum<Channel>(),
            ) {
                order,
                payment,
                decline,
                channel,
                ->
                OrderStatus.fromWire(order.wire) shouldBe order
                PaymentStatus.fromWire(payment.wire) shouldBe payment
                DeclineCategory.fromWire(decline.wire) shouldBe decline
                Channel.fromWire(channel.wire) shouldBe channel
            }
            OrderStatus.fromWire("PLACED") shouldBe null
            PaymentStatus.fromWire("refunded") shouldBe null
            DeclineCategory.fromWire("method_rejected") shouldBe null
            Channel.fromWire("push") shouldBe null
            OrderStatus.entries.filter { it.isTerminal } shouldBe listOf(OrderStatus.DELIVERED, OrderStatus.CANCELLED)
        }

        test("snapshots keep personal data out of toString and scrub it on request") {
            val address = OrderFixtures.ADDRESS
            address.toString() shouldNotContain "Ada"
            address.toString() shouldNotContain "Analytical"
            address.scrubbed() shouldBe
                DeliveryAddress("[redacted]", "[redacted]", null, "[redacted]", null, "[redacted]", "GB")
            OrderFixtures.RECIPIENT.toString() shouldNotContain "ada@"
            Recipient.of("a@b.test", null, emptyList()).channels shouldBe listOf(Channel.EMAIL)
            Recipient.of("a@b.test", "+5511987654321", listOf(Channel.SMS, Channel.SMS)).channels shouldBe
                listOf(Channel.SMS)
            shouldThrow<IllegalArgumentException> { Recipient("a@b.test", null, emptyList()) }
        }

        test("status changes and actors render the history attribution") {
            val at = Instant.parse("2026-10-02T10:15:00Z")
            val account = AccountId(UUID.randomUUID())
            StatusChange.order(null, OrderStatus.PLACED, at, Actor.Shopper(account)) shouldBe
                StatusChange(StatusKind.ORDER, null, "placed", at, account.toString(), null)
            StatusChange.order(
                OrderStatus.PLACED,
                OrderStatus.CANCELLED,
                at,
                Actor.Operator(account),
                CancellationReason.OPERATOR,
            ) shouldBe
                StatusChange(
                    StatusKind.ORDER,
                    "placed",
                    "cancelled",
                    at,
                    account.toString(),
                    CancellationReason.OPERATOR,
                )
            StatusChange.payment(PaymentStatus.PENDING, PaymentStatus.APPROVED, at) shouldBe
                StatusChange(StatusKind.PAYMENT, "pending", "approved", at, "system")
            Actor.System.by shouldBe "system"
            StatusKind.ORDER.wire shouldBe "order"
            StatusKind.PAYMENT.wire shouldBe "payment"
            Caller(account, setOf(Role.SHOPPER, Role.OPERATOR)).let {
                it.isShopper shouldBe true
                it.isOperator shouldBe true
            }
            Caller(account, emptySet()).let {
                it.isShopper shouldBe false
                it.isOperator shouldBe false
            }
        }

        test("identifiers print their UUID") {
            val uuid = UUID.randomUUID()
            listOf(
                OrderId(uuid).toString(),
                AccountId(uuid).toString(),
                ProductId(uuid).toString(),
                AddressId(uuid).toString(),
                ReservationId(uuid).toString(),
                PaymentAttemptId(uuid).toString(),
                RefundId(uuid).toString(),
                IdempotencyKey(uuid).toString(),
            ).forEach { it shouldBe uuid.toString() }
        }
    })
