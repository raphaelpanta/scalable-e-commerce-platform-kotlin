package com.ecommerce.catalog.application

import arrow.core.nonEmptyListOf
import com.ecommerce.catalog.domain.CatalogError
import com.ecommerce.catalog.domain.FieldIssue
import com.ecommerce.catalog.domain.OrderId
import com.ecommerce.catalog.domain.ProductId
import com.ecommerce.catalog.domain.Quantity
import com.ecommerce.catalog.domain.Reservation
import com.ecommerce.catalog.domain.ReservationId
import com.ecommerce.catalog.domain.ReservationState
import com.ecommerce.catalog.domain.SaleState
import com.ecommerce.catalog.domain.StockEffect
import com.ecommerce.catalog.domain.StockLine
import com.ecommerce.catalog.domain.Transition
import com.ecommerce.catalog.domain.UnavailableLine
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant
import java.util.UUID

private const val CORRELATION = "3f6c1b2a-9d4e-4f70-8a15-6b2c7d9e0f13"

private fun order(): OrderId = OrderId(UUID.randomUUID())

/** A stored reservation of [quantity] units of [productId] in [state], with the matching stock bookkeeping. */
private fun Harness.reservation(
    productId: ProductId,
    quantity: Int,
    state: ReservationState = ReservationState.RESERVED,
    expiresAt: Instant? = null,
): Reservation {
    val opened =
        Reservation.open(
            ReservationId(UUID.randomUUID()),
            order(),
            listOf(line(productId, quantity)),
            NOW.minusSeconds(60),
        )
    val stored = opened.copy(state = state, expiresAt = expiresAt ?: opened.expiresAt)
    reservations.store(stored)
    return stored
}

private fun line(
    productId: ProductId,
    quantity: Int,
) = StockLine(productId, Quantity.of(quantity).valid())

class ReservationsTest :
    FunSpec({
        context("reserving") {
            test("every line is reserved at once, the event published and the reservation created") {
                val harness = Harness()
                val espresso = harness.product("Espresso", stock = 5)
                val beans = harness.product("Beans", stock = 10, reserved = 1)
                val orderId = order()

                val reserved =
                    ReserveStock(harness.catalog)(orderId, listOf(espresso.id to 1, beans.id to 2), CORRELATION).value()

                reserved.created shouldBe true
                with(reserved.reservation) {
                    this.orderId shouldBe orderId
                    state shouldBe ReservationState.RESERVED
                    lines shouldContainExactly listOf(line(espresso.id, 1), line(beans.id, 2))
                    createdAt shouldBe NOW
                    expiresAt shouldBe Instant.parse("2026-10-02T10:45:00Z")
                    id.value shouldBe UUID(0L, 1L)
                }
                harness.level(espresso).reserved shouldBe 1
                harness.level(beans).reserved shouldBe 3
                harness.reservations.reservations.values
                    .single() shouldBe reserved.reservation
                harness.events.published shouldContainExactly listOf("reserved:${reserved.reservation.id.value}")
                harness.events.correlationIds shouldContainExactly listOf(CORRELATION)
            }

            test("reserving again for the same order returns the existing reservation unchanged, whatever its state") {
                val harness = Harness()
                val product = harness.product(stock = 5)
                val existing = harness.reservation(product.id, 2, ReservationState.COMMITTED)

                val replay = ReserveStock(harness.catalog)(existing.orderId, listOf(product.id to 4)).value()

                replay shouldBe Reserved(existing, created = false)
                harness.level(product).reserved shouldBe 0
                harness.events.published.shouldBeEmpty()
            }

            test("a shortage lists every short line in request order and reserves nothing") {
                val harness = Harness()
                val espresso = harness.product("Espresso", stock = 5)
                val beans = harness.product("Beans", stock = 1)
                val mug = harness.product("Mug", stock = 3, reserved = 3)

                val refused =
                    ReserveStock(harness.catalog)(order(), listOf(espresso.id to 1, beans.id to 2, mug.id to 1)).error()

                refused shouldBe
                    CatalogError.InsufficientStock(
                        nonEmptyListOf(UnavailableLine(beans.id, 2, 1), UnavailableLine(mug.id, 1, 0)),
                    )
                harness.level(espresso).reserved shouldBe 0
                harness.reservations.reservations.values
                    .shouldBeEmpty()
                harness.events.published.shouldBeEmpty()
            }

            test("withdrawn and unknown products count as unavailable") {
                val harness = Harness()
                val withdrawn = harness.product(stock = 3, saleState = SaleState.WITHDRAWN)
                val unknown = ProductId(UUID.randomUUID())

                ReserveStock(harness.catalog)(order(), listOf(withdrawn.id to 1, unknown to 1)).error() shouldBe
                    CatalogError.InsufficientStock(
                        nonEmptyListOf(UnavailableLine(withdrawn.id, 1, 0), UnavailableLine(unknown, 1, 0)),
                    )
            }

            test("invalid lines are refused before anything is read") {
                val harness = Harness()
                val product = harness.product()
                ReserveStock(harness.catalog)(order(), listOf(product.id to 0)).error() shouldBe
                    CatalogError.Invalid(nonEmptyListOf(FieldIssue("lines[0].quantity", "must be 1 to 99")))
            }

            test("losing the last unit to a concurrent reservation rolls everything back and names the shortage") {
                val harness = Harness()
                val espresso = harness.product("Espresso", stock = 2)
                val beans = harness.product("Beans", stock = 1)
                // The snapshot sees one unit of beans; the guarded update then fails as if another order took it.
                val racing =
                    object : InventoryRepository by harness.inventory {
                        override suspend fun tryReserve(
                            productId: ProductId,
                            quantity: Quantity,
                        ): Boolean {
                            if (productId == beans.id) {
                                harness.inventory.store(harness.level(beans).copy(reserved = 1))
                                return false
                            }
                            return harness.inventory.tryReserve(productId, quantity)
                        }
                    }
                val racingCatalog = harness.catalogWith(inventory = racing)

                val refused = ReserveStock(racingCatalog)(order(), listOf(espresso.id to 2, beans.id to 1)).error()

                refused shouldBe CatalogError.InsufficientStock(nonEmptyListOf(UnavailableLine(beans.id, 1, 0)))
                harness.level(espresso).reserved shouldBe 0
                harness.reservations.reservations.values
                    .shouldBeEmpty()
                harness.transactions.rollbacks shouldBe 1
                harness.events.published.shouldBeEmpty()
            }

            test("a guarded update that fails without a shortage is a conflict") {
                val harness = Harness()
                val product = harness.product(stock = 2)
                harness.inventory.failingReservations = 1

                ReserveStock(harness.catalog)(order(), listOf(product.id to 1)).error() shouldBe
                    CatalogError.ConcurrentUpdate
                harness.level(product).reserved shouldBe 0
            }

            test("a concurrent reservation of the same order wins: its reservation is returned") {
                val harness = Harness()
                val product = harness.product(stock = 2)
                val orderId = order()
                val winner =
                    object : ReservationRepository by harness.reservations {
                        var stored: Reservation? = null

                        override suspend fun insert(reservation: Reservation): Boolean {
                            stored = reservation.copy(id = ReservationId(UUID.randomUUID()))
                            return false
                        }

                        override suspend fun findByOrder(orderId: OrderId): Reservation? = stored
                    }

                val outcome = ReserveStock(harness.catalogWith(reservations = winner))(orderId, listOf(product.id to 1))

                outcome.value() shouldBe Reserved(checkNotNull(winner.stored), created = false)
                harness.level(product).reserved shouldBe 0
                harness.events.published.shouldBeEmpty()
            }
        }

        context("committing and releasing") {
            test("a commit moves on-hand and reserved stock once and is idempotent") {
                val harness = Harness()
                val product = harness.product(stock = 5, reserved = 2)
                val reservation = harness.reservation(product.id, 2)

                val first = CommitReservation(harness.catalog)(reservation.id, CORRELATION).value()
                val second = CommitReservation(harness.catalog)(reservation.id).value()

                first.shouldBeInstanceOf<Transition.Changed>().effect shouldBe StockEffect.COMMIT
                second.shouldBeInstanceOf<Transition.Unchanged>()
                harness.level(product).onHand shouldBe 3
                harness.level(product).reserved shouldBe 0
                harness.reservations.reservations
                    .getValue(reservation.id)
                    .state shouldBe ReservationState.COMMITTED
                harness.events.published shouldContainExactly listOf("committed:${reservation.id.value}")
                harness.events.correlationIds shouldContainExactly listOf(CORRELATION)
            }

            test("a release returns reserved stock once and is idempotent") {
                val harness = Harness()
                val product = harness.product(stock = 5, reserved = 2)
                val reservation = harness.reservation(product.id, 2)

                ReleaseReservation(harness.catalog)(reservation.id).value().shouldBeInstanceOf<Transition.Changed>()
                ReleaseReservation(harness.catalog)(reservation.id).value().shouldBeInstanceOf<Transition.Unchanged>()

                harness.level(product).onHand shouldBe 5
                harness.level(product).reserved shouldBe 0
                harness.events.published shouldContainExactly listOf("released:${reservation.id.value}:CANCELLED")
            }

            test("commit after release and release after commit are conflicts; unknown reservations are not found") {
                val harness = Harness()
                val product = harness.product(stock = 5)
                val released = harness.reservation(product.id, 1, ReservationState.RELEASED)
                val committed = harness.reservation(product.id, 1, ReservationState.COMMITTED)
                val unknown = ReservationId(UUID.randomUUID())

                CommitReservation(harness.catalog)(released.id)
                    .error()
                    .shouldBeInstanceOf<CatalogError.IllegalTransition>()
                    .state shouldBe ReservationState.RELEASED
                ReleaseReservation(harness.catalog)(committed.id)
                    .error()
                    .shouldBeInstanceOf<CatalogError.IllegalTransition>()
                    .state shouldBe ReservationState.COMMITTED
                CommitReservation(harness.catalog)(unknown).error() shouldBe CatalogError.ReservationNotFound(unknown)
                ReleaseReservation(harness.catalog)(unknown).error() shouldBe CatalogError.ReservationNotFound(unknown)
                harness.events.published.shouldBeEmpty()
            }

            test("a transition that loses a race is re-read and tried again, up to three times") {
                val harness = Harness()
                val product = harness.product(stock = 5, reserved = 1)
                val reservation = harness.reservation(product.id, 1)
                harness.reservations.losingUpdates = 2

                CommitReservation(harness.catalog)(reservation.id).value().shouldBeInstanceOf<Transition.Changed>()
                harness.level(product).onHand shouldBe 4

                val other = harness.reservation(product.id, 1)
                harness.inventory.store(harness.level(product).copy(reserved = 1))
                harness.reservations.losingUpdates = 3
                CommitReservation(harness.catalog)(other.id).error() shouldBe CatalogError.ConcurrentUpdate
                harness.reservations.reservations
                    .getValue(other.id)
                    .state shouldBe ReservationState.RESERVED
            }

            test("a stock update that fails rolls the transition back") {
                val harness = Harness()
                val product = harness.product(stock = 5, reserved = 1)
                val reservation = harness.reservation(product.id, 1)
                harness.inventory.failingApplies = 3

                ReleaseReservation(harness.catalog)(reservation.id).error() shouldBe CatalogError.ConcurrentUpdate
                harness.reservations.reservations
                    .getValue(reservation.id)
                    .state shouldBe ReservationState.RESERVED
                harness.level(product).reserved shouldBe 1
                harness.events.published.shouldBeEmpty()
            }
        }

        context("order events") {
            test("OrderPaid commits once; repeated events change nothing") {
                val harness = Harness()
                val product = harness.product(stock = 5, reserved = 2)
                val reservation = harness.reservation(product.id, 2)
                val settle = SettleOrderReservation(harness.catalog)

                settle(reservation.orderId, OrderOutcome.PAID, CORRELATION)
                    .value()
                    .shouldBeInstanceOf<Settlement.Applied>()
                    .transition
                    .shouldBeInstanceOf<Transition.Changed>()
                settle(reservation.orderId, OrderOutcome.PAID, CORRELATION)
                    .value()
                    .shouldBeInstanceOf<Settlement.Applied>()
                    .transition
                    .shouldBeInstanceOf<Transition.Unchanged>()

                harness.level(product).onHand shouldBe 3
                harness.events.published shouldContainExactly listOf("committed:${reservation.id.value}")
                harness.events.correlationIds shouldContainExactly listOf(CORRELATION)
            }

            test("OrderPaymentFailed releases with reason PAYMENT_FAILED and tolerates a committed reservation") {
                val harness = Harness()
                val product = harness.product(stock = 5, reserved = 2)
                val reservation = harness.reservation(product.id, 2)
                val committed = harness.reservation(product.id, 1, ReservationState.COMMITTED)
                val settle = SettleOrderReservation(harness.catalog)

                settle(reservation.orderId, OrderOutcome.PAYMENT_FAILED, null)
                    .value()
                    .shouldBeInstanceOf<Settlement.Applied>()
                settle(committed.orderId, OrderOutcome.PAYMENT_FAILED, null).value() shouldBe Settlement.Ignored

                harness.level(product).reserved shouldBe 0
                harness.events.published shouldContainExactly listOf("released:${reservation.id.value}:PAYMENT_FAILED")
            }

            test("OrderCancelled releases reserved stock, restocks committed stock and ignores released reservations") {
                val harness = Harness()
                val product = harness.product(stock = 5, reserved = 1)
                val reserved = harness.reservation(product.id, 1)
                val committed = harness.reservation(product.id, 2, ReservationState.COMMITTED)
                val released = harness.reservation(product.id, 1, ReservationState.RELEASED)
                val settle = SettleOrderReservation(harness.catalog)

                settle(
                    reserved.orderId,
                    OrderOutcome.PAYMENT_EXPIRED,
                    null,
                ).value().shouldBeInstanceOf<Settlement.Applied>()
                settle(committed.orderId, OrderOutcome.CANCELLED, null).value().shouldBeInstanceOf<Settlement.Applied>()
                settle(released.orderId, OrderOutcome.CANCELLED, null)
                    .value()
                    .shouldBeInstanceOf<Settlement.Applied>()
                    .transition
                    .shouldBeInstanceOf<Transition.Unchanged>()

                harness.level(product).reserved shouldBe 0
                harness.level(product).onHand shouldBe 7
                harness.reservations.reservations
                    .getValue(committed.id)
                    .restocked shouldBe true
                harness.events.published shouldContainExactly
                    listOf("released:${reserved.id.value}:EXPIRED", "released:${committed.id.value}:CANCELLED")
            }

            test("an order without reservation is ignored; a persisting race is an error so the event is retried") {
                val harness = Harness()
                SettleOrderReservation(harness.catalog)(order(), OrderOutcome.PAID, null).value() shouldBe
                    Settlement.Ignored
                val product = harness.product(stock = 5, reserved = 1)
                val reservation = harness.reservation(product.id, 1)
                harness.reservations.losingUpdates = 3
                SettleOrderReservation(harness.catalog)(reservation.orderId, OrderOutcome.PAID, null).error() shouldBe
                    CatalogError.ConcurrentUpdate
                OrderOutcome.PAID.releaseReason shouldBe com.ecommerce.catalog.domain.ReleaseReason.CANCELLED
            }

            test("a reservation that disappears while settling is reported as not found") {
                val harness = Harness()
                val product = harness.product(stock = 5, reserved = 1)
                val reservation = harness.reservation(product.id, 1)
                val vanishing =
                    object : ReservationRepository by harness.reservations {
                        private var reads = 0

                        override suspend fun findByOrder(orderId: OrderId): Reservation? =
                            if (reads++ == 0) harness.reservations.findByOrder(orderId) else null
                    }
                val racingCatalog = harness.catalogWith(reservations = vanishing)
                SettleOrderReservation(racingCatalog)(reservation.orderId, OrderOutcome.PAID, null).error() shouldBe
                    CatalogError.ReservationNotFound(reservation.id)
            }
        }

        context("expiry") {
            test("reservations still reserved after their expiry are released with reason EXPIRED, in batches") {
                val harness = Harness()
                val product = harness.product(stock = 5, reserved = 3)
                val expired = harness.reservation(product.id, 1, expiresAt = NOW.minusSeconds(1))
                val due = harness.reservation(product.id, 1, expiresAt = NOW)
                val open = harness.reservation(product.id, 1, expiresAt = NOW.plusSeconds(1))
                harness.reservation(product.id, 1, ReservationState.COMMITTED, expiresAt = NOW.minusSeconds(1))

                ExpireReservations(harness.catalog)() shouldBe 2

                harness.reservations.expiringLimit shouldBe ExpireReservations.DEFAULT_BATCH
                harness.level(product).reserved shouldBe 1
                harness.reservations.reservations
                    .getValue(open.id)
                    .state shouldBe ReservationState.RESERVED
                harness.events.published shouldContainExactly
                    listOf("released:${expired.id.value}:EXPIRED", "released:${due.id.value}:EXPIRED")
                ExpireReservations(harness.catalog, batchSize = 1)() shouldBe 0
                harness.reservations.expiringLimit shouldBe 1
            }

            test("a reservation committed after it was listed for expiry is left alone") {
                val harness = Harness()
                val product = harness.product(stock = 5, reserved = 1)
                val reservation = harness.reservation(product.id, 1, expiresAt = NOW.minusSeconds(1))
                val committing =
                    object : ReservationRepository by harness.reservations {
                        override suspend fun find(id: ReservationId): Reservation? =
                            harness.reservations.find(id)?.copy(state = ReservationState.COMMITTED)
                    }
                val racingCatalog = harness.catalogWith(reservations = committing)
                ExpireReservations(racingCatalog)() shouldBe 0
                harness.reservations.reservations
                    .getValue(reservation.id)
                    .state shouldBe ReservationState.RESERVED
            }
        }
    })
