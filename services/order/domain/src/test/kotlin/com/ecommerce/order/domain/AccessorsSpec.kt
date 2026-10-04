package com.ecommerce.order.domain

import com.ecommerce.order.domain.OrderFixtures.NOW
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.util.UUID

/** The plain data of the domain exposes exactly what it was built with (read by the adapters). */
class AccessorsSpec :
    FunSpec({
        test("orders and placements expose their snapshot fields") {
            val placement = OrderFixtures.placement()
            placement.number shouldBe OrderNumber.of(LocalDate.of(2026, 10, 2), 1)
            placement.paymentMethodRef shouldBe "tok_sim_approve_4242"
            val order = checkNotNull(Order.place(placement, Order.DEFAULT_PAYMENT_WINDOW).getOrNull()).order
            order.number.value shouldBe "ORD-20261002-0001"
            order.paymentMethodRef shouldBe "tok_sim_approve_4242"
            order.copy(version = 7).version shouldBe 7
            order.lines
                .single()
                .quantity.value shouldBe 1
            order.total.currency shouldBe "BRL"
        }

        test("snapshots expose their fields") {
            val address = OrderFixtures.ADDRESS
            listOf(
                address.recipientName,
                address.line1,
                address.line2,
                address.city,
                address.region,
                address.postalCode,
                address.country,
            ) shouldBe listOf("Ada Lovelace", "12 Analytical Street", "Flat 2", "London", "England", "N1 9GU", "GB")
            OrderFixtures.RECIPIENT.email shouldBe "ada@example.test"
            OrderFixtures.RECIPIENT.phone shouldBe "+5511987654321"
            val change =
                StatusChange.order(
                    OrderStatus.PLACED,
                    OrderStatus.CANCELLED,
                    NOW,
                    Actor.System,
                    CancellationReason.OPERATOR,
                )
            listOf(change.from, change.status, change.by) shouldBe listOf("placed", "cancelled", "system")
            change.reason shouldBe CancellationReason.OPERATOR
            Cancellation(CancellationReason.OPERATOR, NOW, "x").by shouldBe "x"
        }

        test("checkout values expose their fields") {
            val productId = ProductId(UUID.randomUUID())
            val line = CartLine("line-1", productId, "SKU-1", "Mug", Quantity(2), Money(100, "BRL"))
            line.lineId shouldBe "line-1"
            line.sku shouldBe "SKU-1"
            Cart("rev-1", listOf(line)).revision shouldBe "rev-1"
            ProductPrice(productId, "SKU-1", "Mug", Money(100, "BRL"), 5, true).available shouldBe 5
            StockLine(productId, Quantity(3)).quantity.value shouldBe 3
            val unavailable = UnavailableLine(productId, "Mug", 2, 1)
            listOf(unavailable.name, unavailable.requestedQuantity, unavailable.availableQuantity) shouldBe
                listOf("Mug", 2, 1)
            val request = CheckoutRequest(AddressId(UUID.randomUUID()), "rev-1", "card", "tok")
            listOf(request.cartRevision, request.paymentMethodType, request.paymentToken) shouldBe
                listOf("rev-1", "card", "tok")
            val caller = Caller(OrderFixtures.SHOPPER, setOf(Role.SHOPPER))
            caller.roles shouldBe setOf(Role.SHOPPER)
            PageRequest(2, 10).let { listOf(it.page, it.size) shouldBe listOf(2, 10) }
            Page(listOf("a"), PageRequest(0, 1), 9).let {
                it.items shouldBe listOf("a")
                it.totalItems shouldBe 9
            }
        }

        test("errors and stored answers expose their fields") {
            OrderError.Invalid("size", "too big").let { listOf(it.field, it.reason) shouldBe listOf("size", "too big") }
            OrderError.PriceChanged(emptyList(), "rev-2").let {
                it.changedLines shouldBe emptyList()
                it.currentCartRevision shouldBe "rev-2"
            }
            OrderError.InvalidTransition(OrderStatus.PLACED, OrderStatus.SHIPPED, "no").reason shouldBe "no"
            val stored = StoredResponse(201, "{}")
            listOf(stored.status, stored.body) shouldBe listOf(201, "{}")
            val orderId = OrderId(UUID.randomUUID())
            val record =
                IdempotencyRecord(OrderFixtures.KEY, OrderFixtures.SHOPPER, "hash", orderId, stored, NOW, NOW)
            listOf(record.requestHash, record.orderId, record.response) shouldBe listOf("hash", orderId, stored)
        }
    })
