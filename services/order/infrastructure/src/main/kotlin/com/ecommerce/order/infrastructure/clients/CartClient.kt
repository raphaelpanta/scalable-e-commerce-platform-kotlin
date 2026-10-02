package com.ecommerce.order.infrastructure.clients

import com.ecommerce.order.application.CartPort
import com.ecommerce.order.domain.AccountId
import com.ecommerce.order.domain.Cart
import com.ecommerce.order.domain.CartLine
import com.ecommerce.order.domain.Money
import com.ecommerce.order.domain.ProductId
import com.ecommerce.order.domain.Quantity
import com.ecommerce.platform.http.awaitBodyOrProblem
import com.ecommerce.platform.http.awaitOptionalBodyOrProblem
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import java.util.UUID

/** `AccountCart` of cart-internal.yaml (the fields the order reads). */
data class AccountCartJson(
    val cartId: UUID,
    val revision: String,
    val lines: List<Line>,
) {
    /** `AccountCartLine`. */
    data class Line(
        val lineId: String,
        val productId: UUID,
        val sku: String,
        val name: String,
        val quantity: Int,
        val priceAtAdd: MoneyJson,
    )

    fun toCart(): Cart =
        Cart(
            revision,
            lines.map {
                CartLine(
                    it.lineId,
                    ProductId(it.productId),
                    it.sku,
                    it.name,
                    Quantity(it.quantity),
                    Money(it.priceAtAdd.amountMinor, it.priceAtAdd.currency),
                )
            },
        )
}

/** [CartPort] over cart-internal.yaml (`GET /internal/carts/by-account/{id}`, `POST .../clear`). */
class CartClient(
    private val client: WebClient,
) : CartPort {
    override suspend fun cartOf(accountId: AccountId): Cart? =
        required(SERVICE) {
            client
                .get()
                .uri("/internal/carts/by-account/{accountId}", accountId.value)
                .accept(MediaType.APPLICATION_JSON)
                .awaitBodyOrProblem<AccountCartJson>()
        }.fold(
            { absentIfNotFound(SERVICE, it) },
            { it.toCart() },
        )

    override suspend fun clear(accountId: AccountId) {
        tolerated("Clearing the cart") {
            client
                .post()
                .uri("/internal/carts/by-account/{accountId}/clear", accountId.value)
                .accept(MediaType.APPLICATION_JSON)
                .awaitOptionalBodyOrProblem<String>()
                .leftOrNull()
        }
    }

    private companion object {
        const val SERVICE = "cart"
    }
}
