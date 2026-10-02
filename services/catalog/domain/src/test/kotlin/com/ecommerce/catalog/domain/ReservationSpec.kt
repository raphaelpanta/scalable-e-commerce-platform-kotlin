package com.ecommerce.catalog.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val LATER: Instant = NOW.plusSeconds(120)

private fun newId() = ReservationId(UUID.randomUUID())

class ReservationSpec :
    FunSpec({
        context("inventory") {
            test("reserving fails when fewer units are available than requested and never goes negative") {
                checkAll(DomainArbs.stock, DomainArbs.stock, DomainArbs.quantity) { onHand, reservedRaw, quantity ->
                    val level = InventoryLevel(productId(), onHand, reservedRaw.coerceAtMost(onHand))
                    val result = level.reserve(quantity)
                    if (level.available >= quantity.value) {
                        val reserved = result.value()
                        reserved.reserved shouldBe level.reserved + quantity.value
                        reserved.onHand shouldBe level.onHand
                        reserved.available shouldBe level.available - quantity.value
                        (reserved.available >= 0) shouldBe true
                    } else {
                        result.error() shouldBe UnavailableLine(level.productId, quantity.value, level.available)
                    }
                }
            }

            test("the last unit can be reserved exactly once") {
                val level = InventoryLevel(productId(), onHand = 1, reserved = 0)
                val first = level.reserve(quantity(1)).value()
                first.available shouldBe 0
                first.reserve(quantity(1)).error().available shouldBe 0
            }

            test("commit, release and restock move on-hand and reserved units") {
                val level = InventoryLevel(productId(), onHand = 10, reserved = 4, version = 3)
                level.apply(StockEffect.COMMIT, quantity(3)) shouldBe level.copy(onHand = 7, reserved = 1, version = 4)
                level.apply(StockEffect.RELEASE, quantity(3)) shouldBe level.copy(reserved = 1, version = 4)
                level.apply(StockEffect.RESTOCK, quantity(3)) shouldBe level.copy(onHand = 13, version = 4)
                level.apply(StockEffect.RESERVE, quantity(3)) shouldBe level.copy(reserved = 7, version = 4)
                shouldThrow<IllegalArgumentException> { level.apply(StockEffect.RELEASE, quantity(5)) }
                shouldThrow<IllegalArgumentException> { level.apply(StockEffect.RESERVE, quantity(7)) }
            }

            test("stock effects are signed per column") {
                StockEffect.entries.map { it.onHandDelta(2) to it.reservedDelta(2) } shouldContainExactly
                    listOf(0 to 2, -2 to -2, 0 to -2, 2 to 0)
            }

            test("a level never has negative or excess reserved units") {
                shouldThrow<IllegalArgumentException> { InventoryLevel(productId(), 1, -1) }
                shouldThrow<IllegalArgumentException> { InventoryLevel(productId(), 1, 2) }
                InventoryLevel(productId(), 2, 2).available shouldBe 0
                InventoryLevel(productId(), 0, 0).available shouldBe 0
            }
        }

        context("opening") {
            test("a reservation opens reserved, expiring after its lifetime in whole seconds") {
                val opened = reservation(1, 2)
                opened.state shouldBe ReservationState.RESERVED
                opened.createdAt shouldBe NOW
                opened.expiresAt shouldBe Instant.parse("2026-10-02T10:45:00Z")
                opened.resolvedAt shouldBe null
                opened.restocked shouldBe false
                opened.version shouldBe 0
                val shortLived =
                    Reservation.open(newId(), opened.orderId, lines(1), NOW, Duration.ofSeconds(1))
                shortLived.expiresAt shouldBe Instant.parse("2026-10-02T10:00:01Z")
                shouldThrow<IllegalArgumentException> {
                    Reservation.open(newId(), opened.orderId, lines(1), NOW, Duration.ofMillis(999))
                }
                Reservation.DEFAULT_TTL shouldBe Duration.ofMinutes(45)
            }

            test("lines are 1 to 100 distinct products of 1 to 99 units, every broken rule reported") {
                checkAll(Arb.list(DomainArbs.quantity, 1..Reservation.MAX_LINES)) { quantities ->
                    val requested = quantities.map { productId() to it.value }
                    Reservation.lines(requested).value().map { it.productId to it.quantity.value } shouldBe requested
                }
                Reservation
                    .lines(emptyList())
                    .error()
                    .issues
                    .map { it.reason } shouldContainExactly
                    listOf("must contain 1 to 100 lines")
                val tooMany = (0..Reservation.MAX_LINES).map { productId() to 1 }
                Reservation
                    .lines(tooMany)
                    .error()
                    .issues
                    .single()
                    .field shouldBe "lines"
                val repeated = productId()
                val invalid = Reservation.lines(listOf(repeated to 0, repeated to 100)).error().issues
                invalid shouldContainExactly
                    listOf(
                        FieldIssue("lines", "must not repeat a productId"),
                        FieldIssue("lines[0].quantity", "must be 1 to 99"),
                        FieldIssue("lines[1].quantity", "must be 1 to 99"),
                    )
            }

            test("a reservation keeps its invariants") {
                val valid = reservation(1)
                shouldThrow<IllegalArgumentException> { valid.copy(lines = emptyList()) }
                shouldThrow<IllegalArgumentException> { valid.copy(lines = valid.lines + valid.lines) }
                shouldThrow<IllegalArgumentException> { valid.copy(expiresAt = valid.createdAt) }
            }

            test("shortages list every short line in request order; unknown and withdrawn count as 0") {
                checkAll(Arb.list(DomainArbs.quantity, 1..10), Arb.int(0..120)) { quantities, stock ->
                    val requested = quantities.map { StockLine(productId(), it) }
                    val shortages = Reservation.shortages(requested) { stock }
                    shortages shouldBe
                        requested
                            .filter { it.quantity.value > stock }
                            .map { UnavailableLine(it.productId, it.quantity.value, stock) }
                }
                val requested = lines(1, 2, 3)
                val available = mapOf(requested[0].productId to 1, requested[1].productId to 1)
                Reservation.shortages(requested) { available[it] ?: -5 } shouldContainExactly
                    listOf(
                        UnavailableLine(requested[1].productId, 2, 1),
                        UnavailableLine(requested[2].productId, 3, 0),
                    )
                Reservation.shortages(lines(2)) { 2 }.shouldBeEmpty()
            }
        }

        context("transitions") {
            test("commit is legal once from reserved and idempotent afterwards") {
                val opened = reservation(1)
                val committed = opened.commit(LATER).value().shouldBeInstanceOf<Transition.Changed>()
                committed.effect shouldBe StockEffect.COMMIT
                committed.reservation.state shouldBe ReservationState.COMMITTED
                committed.reservation.resolvedAt shouldBe LATER
                committed.reservation.version shouldBe opened.version + 1
                committed.reservation.restocked shouldBe false
                committed.reservation.commit(LATER).value() shouldBe Transition.Unchanged(committed.reservation)
            }

            test("release is legal once from reserved and idempotent afterwards") {
                val released = reservation(2).release(LATER).value().shouldBeInstanceOf<Transition.Changed>()
                released.effect shouldBe StockEffect.RELEASE
                released.reservation.state shouldBe ReservationState.RELEASED
                released.reservation.resolvedAt shouldBe LATER
                released.reservation.release(LATER).value() shouldBe Transition.Unchanged(released.reservation)
            }

            test("commit after release and release after commit are refused") {
                val released = reservation(1, state = ReservationState.RELEASED)
                released.commit(LATER).error() shouldBe
                    CatalogError.IllegalTransition(released.id, ReservationState.RELEASED, "committed")
                val committed = reservation(1, state = ReservationState.COMMITTED)
                committed.release(LATER).error() shouldBe
                    CatalogError.IllegalTransition(committed.id, ReservationState.COMMITTED, "released")
            }

            test("a cancellation releases reserved stock, restocks committed stock and is idempotent") {
                val fromReserved = reservation(1).cancel(LATER).shouldBeInstanceOf<Transition.Changed>()
                fromReserved.effect shouldBe StockEffect.RELEASE
                fromReserved.reservation.restocked shouldBe false
                val committed = reservation(1, state = ReservationState.COMMITTED)
                val fromCommitted = committed.cancel(LATER).shouldBeInstanceOf<Transition.Changed>()
                fromCommitted.effect shouldBe StockEffect.RESTOCK
                fromCommitted.reservation.state shouldBe ReservationState.RELEASED
                fromCommitted.reservation.restocked shouldBe true
                fromCommitted.reservation.resolvedAt shouldBe LATER
                fromCommitted.reservation.cancel(LATER) shouldBe Transition.Unchanged(fromCommitted.reservation)
            }

            test("available stock never becomes negative through any sequence of transitions") {
                checkAll(DomainArbs.quantity, Arb.int(0..3)) { quantity, path ->
                    val productId = productId()
                    var level = InventoryLevel(productId, onHand = quantity.value, reserved = 0)
                    level = level.reserve(quantity).value()
                    val opened =
                        Reservation.open(
                            ReservationId(UUID.randomUUID()),
                            OrderId(UUID.randomUUID()),
                            listOf(StockLine(productId, quantity)),
                            NOW,
                        )
                    val transitions =
                        when (path) {
                            0 -> listOf(opened.commit(LATER).value())
                            1 -> listOf(opened.release(LATER).value())
                            2 -> listOf(opened.cancel(LATER))
                            else -> opened.commit(LATER).value().let { listOf(it, it.reservation.cancel(LATER)) }
                        }
                    transitions.filterIsInstance<Transition.Changed>().forEach {
                        level = level.apply(it.effect, quantity)
                    }
                    (level.available >= 0) shouldBe true
                    level.reserved shouldBe 0
                    level.onHand shouldBe if (path == 0) 0 else quantity.value
                }
            }

            test("a reservation expires when still reserved at its expiry time") {
                val opened = reservation(1)
                opened.isExpiredAt(opened.expiresAt.minusNanos(1000)) shouldBe false
                opened.isExpiredAt(opened.expiresAt) shouldBe true
                opened.isExpiredAt(opened.expiresAt.plusSeconds(1)) shouldBe true
                opened.copy(state = ReservationState.COMMITTED).isExpiredAt(opened.expiresAt) shouldBe false
                opened.copy(state = ReservationState.RELEASED).isExpiredAt(opened.expiresAt) shouldBe false
            }
        }
    })
