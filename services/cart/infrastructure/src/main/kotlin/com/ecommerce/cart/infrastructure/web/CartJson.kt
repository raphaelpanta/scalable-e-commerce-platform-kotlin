package com.ecommerce.cart.infrastructure.web

import com.ecommerce.cart.application.AccountCart
import com.ecommerce.cart.application.MergeResult
import com.ecommerce.cart.domain.CappedLine
import com.ecommerce.cart.domain.Money
import com.ecommerce.cart.domain.PricedCart
import com.ecommerce.cart.domain.PricedLine
import java.time.Instant
import java.util.UUID

/** cart.yaml `AddLineRequest`; fields are nullable so that a missing one is answered with a precise 400. */
data class AddLineRequest(
    val productId: UUID? = null,
    val quantity: Int? = null,
)

/** cart.yaml `UpdateLineRequest`. */
data class UpdateLineRequest(
    val quantity: Int? = null,
)

/** `{"amountMinor": 1999, "currency": "BRL"}`. */
data class MoneyJson(
    val amountMinor: Long,
    val currency: String,
)

/** cart.yaml `CartLine`. */
data class CartLineJson(
    val id: UUID,
    val productId: UUID,
    val productName: String,
    val quantity: Int,
    val priceAtAdd: MoneyJson,
    val currentPrice: MoneyJson,
    val priceChanged: Boolean,
    val lineTotal: MoneyJson,
)

/** cart.yaml `Cart`. */
data class CartJson(
    val id: UUID,
    val revision: String,
    val lines: List<CartLineJson>,
    val total: MoneyJson,
    val updatedAt: Instant,
)

/** cart.yaml `CappedLine`. */
data class CappedLineJson(
    val productId: UUID,
    val requestedQuantity: Int,
    val appliedQuantity: Int,
)

/** cart.yaml `MergeResult`. */
data class MergeResultJson(
    val cart: CartJson,
    val cappedLines: List<CappedLineJson>,
)

/** cart-internal.yaml `AccountCartLine`. */
data class AccountCartLineJson(
    val lineId: UUID,
    val productId: UUID,
    val sku: String,
    val name: String,
    val quantity: Int,
    val priceAtAdd: MoneyJson,
)

/** cart-internal.yaml `AccountCart`. */
data class AccountCartJson(
    val cartId: UUID,
    val revision: String,
    val lines: List<AccountCartLineJson>,
    val total: MoneyJson,
)

fun Money.toJson(): MoneyJson = MoneyJson(amountMinor, currency)

fun PricedLine.toJson(): CartLineJson =
    CartLineJson(
        id = line.id.value,
        productId = line.productId.value,
        productName = line.name,
        quantity = line.quantity.value,
        priceAtAdd = line.priceAtAdd.toJson(),
        currentPrice = currentPrice.toJson(),
        priceChanged = priceChanged,
        lineTotal = lineTotal.toJson(),
    )

fun PricedCart.toJson(): CartJson =
    CartJson(cart.id.value, revision.value, lines.map { it.toJson() }, total.toJson(), cart.updatedAt)

fun CappedLine.toJson(): CappedLineJson = CappedLineJson(productId.value, requestedQuantity, appliedQuantity)

fun MergeResult.toJson(): MergeResultJson = MergeResultJson(cart.toJson(), cappedLines.map { it.toJson() })

fun AccountCart.toJson(): AccountCartJson =
    AccountCartJson(
        cartId = priced.cart.id.value,
        revision = priced.revision.value,
        lines =
            priced.cart.lines.map {
                AccountCartLineJson(
                    it.id.value,
                    it.productId.value,
                    it.sku,
                    it.name,
                    it.quantity.value,
                    it.priceAtAdd.toJson(),
                )
            },
        total = totalAtAdd.toJson(),
    )
