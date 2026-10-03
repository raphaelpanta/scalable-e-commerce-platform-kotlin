package com.ecommerce.catalog.application

import arrow.core.Either
import com.ecommerce.catalog.domain.CatalogError
import com.ecommerce.catalog.domain.OrderId
import com.ecommerce.catalog.domain.Product
import com.ecommerce.catalog.domain.Quantity
import com.ecommerce.catalog.domain.Reservation
import com.ecommerce.catalog.domain.ReservationId
import com.ecommerce.catalog.domain.ReservationState
import com.ecommerce.catalog.domain.StockLine
import com.ecommerce.catalog.domain.Transition
import com.ecommerce.catalog.domain.UnavailableLine
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import java.time.Duration
import java.util.UUID

/** What a step did to a reservation. */
private enum class Observed {
    MOVED,
    STAYED,
    REFUSED,
    IGNORED,
}

/**
 * What the model expects a step to do: the observation and, when the reservation moves, its new state and the event
 * (its [type] and release [reason]).
 */
private data class Expected(
    val observed: Observed,
    val to: ReservationState? = null,
    val type: String? = null,
    val reason: String? = null,
    val restock: Boolean = false,
)

private val STAYS = Expected(Observed.STAYED)

/** The reservation state machine of data-model section 3.2, written independently of the domain. */
private fun model(
    state: ReservationState,
    step: ReservationStep,
    lapsed: Boolean,
): Expected =
    when (step) {
        ReservationStep.Commit -> {
            commitModel(state, Expected(Observed.REFUSED))
        }

        ReservationStep.Release -> {
            releaseModel(state, "CANCELLED", Expected(Observed.REFUSED))
        }

        is ReservationStep.Settle -> {
            when (step.outcome) {
                OrderOutcome.PAID -> commitModel(state, Expected(Observed.IGNORED))
                OrderOutcome.PAYMENT_FAILED -> releaseModel(state, "PAYMENT_FAILED", Expected(Observed.IGNORED))
                OrderOutcome.CANCELLED -> cancelModel(state, "CANCELLED")
                OrderOutcome.PAYMENT_EXPIRED -> cancelModel(state, "EXPIRED")
            }
        }

        ReservationStep.Expire -> {
            if (state == ReservationState.RESERVED && lapsed) {
                moves(ReservationState.RELEASED, "released", "EXPIRED")
            } else {
                STAYS
            }
        }
    }

private fun moves(
    to: ReservationState,
    type: String,
    reason: String? = null,
    restock: Boolean = false,
) = Expected(Observed.MOVED, to, type, reason, restock)

private fun commitModel(
    state: ReservationState,
    refusal: Expected,
): Expected =
    when (state) {
        ReservationState.RESERVED -> moves(ReservationState.COMMITTED, "committed")
        ReservationState.COMMITTED -> STAYS
        ReservationState.RELEASED -> refusal
    }

private fun releaseModel(
    state: ReservationState,
    reason: String,
    refusal: Expected,
): Expected =
    when (state) {
        ReservationState.RESERVED -> moves(ReservationState.RELEASED, "released", reason)
        ReservationState.RELEASED -> STAYS
        ReservationState.COMMITTED -> refusal
    }

private fun cancelModel(
    state: ReservationState,
    reason: String,
): Expected =
    when (state) {
        ReservationState.RESERVED -> moves(ReservationState.RELEASED, "released", reason)
        ReservationState.COMMITTED -> moves(ReservationState.RELEASED, "released", reason, restock = true)
        ReservationState.RELEASED -> STAYS
    }

private fun Either<CatalogError, Transition>.observed(): Observed =
    fold(
        { if (it is CatalogError.IllegalTransition) Observed.REFUSED else error("unexpected failure $it") },
        { if (it is Transition.Changed) Observed.MOVED else Observed.STAYED },
    )

/** Applies [step] to [reservation] through the use cases. */
private suspend fun Harness.apply(
    step: ReservationStep,
    reservation: Reservation,
): Observed =
    when (step) {
        ReservationStep.Commit -> {
            CommitReservation(catalog)(reservation.id).observed()
        }

        ReservationStep.Release -> {
            ReleaseReservation(catalog)(reservation.id).observed()
        }

        is ReservationStep.Settle -> {
            when (val settlement = SettleOrderReservation(catalog)(reservation.orderId, step.outcome, null).value()) {
                is Settlement.Applied -> Either.Right(settlement.transition).observed()
                Settlement.Ignored -> Observed.IGNORED
            }
        }

        ReservationStep.Expire -> {
            if (ExpireReservations(catalog)() == 1) Observed.MOVED else Observed.STAYED
        }
    }

private fun line(
    product: Product,
    units: Int,
) = StockLine(product.id, Quantity.of(units).valid())

/** Stock levels of 1..6 products and a cart over them. */
private val stocksAndCart: Arb<Pair<List<StockSpec>, List<Pair<Int, Int>>>> =
    Arb.list(CatalogArbs.stock, 1..6).flatMap { stocks -> CatalogArbs.cart(stocks.size).map { stocks to it } }

class ReservationsPropertySpec :
    FunSpec({
        test("a reservation is all or nothing: it holds every line or names exactly the short ones") {
            checkAll(stocksAndCart) { (stocks, cart) ->
                val harness = Harness()
                val products =
                    stocks.map { harness.product(stock = it.onHand, reserved = it.reserved, saleState = it.saleState) }
                val requested = cart.map { (slot, units) -> products[slot].id to units }
                val before = products.associate { it.id to harness.level(it) }
                val sellable = products.associate { it.id to if (it.isActive) harness.level(it).available else 0 }

                val outcome = ReserveStock(harness.catalog)(OrderId(UUID.randomUUID()), requested)

                val short = requested.filter { (id, units) -> sellable.getValue(id) < units }
                if (short.isEmpty()) {
                    val reserved = outcome.value()
                    reserved.created shouldBe true
                    reserved.reservation.lines shouldContainExactly
                        cart.map { (slot, units) -> line(products[slot], units) }
                    products.forEach { product ->
                        val units = requested.firstOrNull { it.first == product.id }?.second ?: 0
                        harness.level(product).reserved shouldBe before.getValue(product.id).reserved + units
                        harness.level(product).onHand shouldBe before.getValue(product.id).onHand
                    }
                    harness.events.published shouldContainExactly listOf("reserved:${reserved.reservation.id.value}")
                } else {
                    outcome.error().shouldBeInstanceOf<CatalogError.InsufficientStock>().lines shouldContainExactly
                        short.map { (id, units) -> UnavailableLine(id, units, sellable.getValue(id)) }
                    products.forEach { harness.level(it) shouldBe before.getValue(it.id) }
                    harness.reservations.reservations.values
                        .shouldBeEmpty()
                    harness.events.published.shouldBeEmpty()
                }
            }
        }

        test("orders competing for one product never reserve more than is available, first come first served") {
            checkAll(CatalogArbs.activeStock, Arb.list(CatalogArbs.quantity, 1..8)) { stock, orders ->
                val harness = Harness()
                val product = harness.product(stock = stock.onHand, reserved = stock.reserved)
                var remaining = stock.available

                orders.forEach { units ->
                    val outcome = ReserveStock(harness.catalog)(OrderId(UUID.randomUUID()), listOf(product.id to units))
                    outcome.isRight() shouldBe (units <= remaining)
                    if (units <= remaining) remaining -= units
                    harness.level(product).available shouldBe remaining
                }
                harness.level(product).onHand shouldBe stock.onHand
            }
        }

        test("reserving again for the same order replays the stored reservation and moves nothing") {
            checkAll(CatalogArbs.activeStock, CatalogArbs.quantity, CatalogArbs.quantity) { stock, first, second ->
                val harness = Harness()
                val product = harness.product(stock = stock.onHand + first, reserved = stock.reserved)
                val orderId = OrderId(UUID.randomUUID())
                val created = ReserveStock(harness.catalog)(orderId, listOf(product.id to first)).value()
                val level = harness.level(product)

                val replay = ReserveStock(harness.catalog)(orderId, listOf(product.id to second)).value()

                replay shouldBe Reserved(created.reservation, created = false)
                harness.level(product) shouldBe level
                harness.events.published.size shouldBe 1
            }
        }

        test("commits, releases, order events and expiry follow the state machine and conserve stock") {
            checkAll(CatalogArbs.activeStock, CatalogArbs.quantity, CatalogArbs.lifetime, CatalogArbs.steps) {
                stock,
                units,
                lifetime,
                steps,
                ->
                val harness = Harness()
                val product = harness.product(stock = stock.onHand + units, reserved = stock.reserved + units)
                val opened =
                    Reservation.open(
                        ReservationId(UUID.randomUUID()),
                        OrderId(UUID.randomUUID()),
                        listOf(line(product, units)),
                        NOW.minus(Duration.ofHours(1)),
                        lifetime,
                    )
                harness.reservations.store(opened)
                val lapsed = lifetime < Duration.ofHours(1)
                var state = ReservationState.RESERVED
                var restocked = false
                val events = mutableListOf<String>()

                steps.forEach { step ->
                    val expected = model(state, step, lapsed)
                    harness.apply(step, opened) shouldBe expected.observed
                    if (expected.to != null) {
                        state = expected.to
                        restocked = expected.restock
                        events +=
                            listOfNotNull(expected.type, opened.id.value.toString(), expected.reason).joinToString(":")
                    }
                    val stored = harness.reservations.reservations.getValue(opened.id)
                    stored.state shouldBe state
                    stored.restocked shouldBe restocked
                    // Conservation: units are held while reserved and leave the shelf only once committed.
                    val held = if (state == ReservationState.RESERVED) units else 0
                    val shipped = if (state == ReservationState.COMMITTED) units else 0
                    harness.level(product).reserved shouldBe stock.reserved + held
                    harness.level(product).onHand shouldBe stock.onHand + units - shipped
                }
                harness.events.published shouldContainExactly events
            }
        }
    })
