package com.ecommerce.catalog.domain

import arrow.core.Either
import io.kotest.assertions.fail
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.uuid
import java.time.Instant
import java.util.UUID

const val BRL = "BRL"
val NOW: Instant = Instant.parse("2026-10-02T10:00:00.123456Z")

fun <E, T> Either<E, T>.value(): T = fold({ fail("expected a value but was $it") }, { it })

fun <E, T> Either<E, T>.error(): E = fold({ it }, { fail("expected an error but was $it") })

fun productId(): ProductId = ProductId(UUID.randomUUID())

fun categoryId(): CategoryId = CategoryId(UUID.randomUUID())

fun quantity(value: Int): Quantity = Quantity.of(value).value()

fun details(
    name: String = "Espresso Machine",
    priceMinor: Long = 14900,
    categoryId: CategoryId = categoryId(),
): ProductDetails = ProductDetails.of(name, "A machine.", priceMinor, BRL, categoryId).value()

fun product(
    id: ProductId = productId(),
    saleState: SaleState = SaleState.ACTIVE,
): Product = Product.create(id, Sku.generatedFor(id), details(), NOW).copy(saleState = saleState)

fun image(primary: Boolean = false): ProductImage =
    ProductImage(ImageId(UUID.randomUUID()), ImageRef.of("https://cdn.example.test/a.jpg").value(), "alt", primary)

fun lines(vararg quantities: Int): List<StockLine> = quantities.map { StockLine(productId(), quantity(it)) }

fun reservation(
    vararg quantities: Int,
    state: ReservationState = ReservationState.RESERVED,
): Reservation =
    Reservation
        .open(ReservationId(UUID.randomUUID()), OrderId(UUID.randomUUID()), lines(*quantities), NOW)
        .copy(state = state)

/** Generators of the catalogue domain's values. */
object DomainArbs {
    val quantity: Arb<Quantity> = Arb.int(Quantity.MIN..Quantity.MAX).map(::quantity)
    val productId: Arb<ProductId> = Arb.uuid().map(::ProductId)
    val stock: Arb<Int> = Arb.int(0..500)
}
