package com.ecommerce.order.infrastructure

import com.ecommerce.platform.testing.InternalToken
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.MappingBuilder
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.matching
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.matching.StringValuePattern
import java.util.UUID

private const val CREATED = 201
private const val NO_CONTENT = 204
private const val CONFLICT = 409
private const val UNAVAILABLE = 503
private const val JSON = "application/json"
private const val PROBLEM_JSON = "application/problem+json"

/**
 * The checkout fixture of the storefront pact (pact-matrix.md "Storefront to order", frontend/pact/order.pact.test.ts):
 * the cart of ana@example.com at revision [REVISION] with an Espresso Machine and two bags of Coffee Beans, her owned
 * address, and the card tokens of the payment simulator.
 */
object StorefrontCart {
    const val REVISION = "rev-7f3a9c21"

    /** The revision the cart moved to after a price change. */
    const val CURRENT_REVISION = "rev-9d4c1b37"
    const val ESPRESSO_SKU = "ESP-MACH-01"
    const val ESPRESSO_NAME = "Espresso Machine"
    const val ESPRESSO_PRICE = 14_900L
    const val ESPRESSO_NEW_PRICE = 15_900L
    const val ESPRESSO_QUANTITY = 1
    const val BEANS_SKU = "CB-1KG"
    const val BEANS_NAME = "Coffee Beans 1kg"
    const val BEANS_PRICE = 2_450L
    const val BEANS_QUANTITY = 2
    const val AVAILABLE = 5
    const val APPROVE_TOKEN = "tok_sim_approve_4242"
    const val DECLINE_TOKEN_PREFIX = "tok_sim_decline"
    const val UNREACHABLE_TOKEN = "tok_sim_unreachable"
}

/**
 * WireMock stand-ins of cart, catalog, identity and payment for the storefront provider states, keyed to
 * [StorefrontCart] and the fixed identifiers of [PactValues]; one server, the internal paths do not overlap.
 */
@Suppress("TooManyFunctions") // one stub per internal endpoint the checkout calls
class StorefrontStubs(
    private val wiremock: WireMockServer,
) {
    fun reset() {
        wiremock.resetAll()
    }

    /**
     * Everything a checkout of ana reads: her cart at [revision] with the prices it recorded, the catalogue's current
     * prices (the espresso machine at [espressoPrice]), her address and contact, a granted reservation and the
     * simulator's charges (approved, declined by token prefix, payment service unreachable for the pending token),
     * each charge answered after [chargeDelayMillis].
     */
    fun checkout(
        revision: String = StorefrontCart.REVISION,
        espressoPrice: Long = StorefrontCart.ESPRESSO_PRICE,
        chargeDelayMillis: Int = 0,
    ) {
        cart(revision)
        pricing(espressoPrice)
        identity()
        reserve()
        transitions()
        charges(chargeDelayMillis)
    }

    /** `GET /internal/carts/by-account/{ana}` at [revision], and the clear after a paid checkout. */
    fun cart(revision: String) {
        val total =
            StorefrontCart.ESPRESSO_PRICE * StorefrontCart.ESPRESSO_QUANTITY +
                StorefrontCart.BEANS_PRICE * StorefrontCart.BEANS_QUANTITY
        val espresso =
            cartLine(
                PactValues.LINE_1,
                PactValues.ESPRESSO,
                StorefrontCart.ESPRESSO_SKU,
                StorefrontCart.ESPRESSO_NAME,
                StorefrontCart.ESPRESSO_QUANTITY,
                StorefrontCart.ESPRESSO_PRICE,
            )
        val beans =
            cartLine(
                PactValues.LINE_2,
                PactValues.BEANS,
                StorefrontCart.BEANS_SKU,
                StorefrontCart.BEANS_NAME,
                StorefrontCart.BEANS_QUANTITY,
                StorefrontCart.BEANS_PRICE,
            )
        wiremock.stubFor(
            internal(get(urlPathEqualTo("/internal/carts/by-account/${PactValues.ADA}"))).willReturn(
                okJson(
                    """{"cartId":"${PactValues.CART}","revision":"$revision","lines":[$espresso,$beans],
                    |"total":{"amountMinor":$total,"currency":"BRL"}}
                    """.trimMargin(),
                ),
            ),
        )
        wiremock.stubFor(
            internal(post(urlPathEqualTo("/internal/carts/by-account/${PactValues.ADA}/clear")))
                .willReturn(aResponse().withStatus(NO_CONTENT)),
        )
    }

    /** `POST /internal/products/pricing`: both products active, the espresso machine at [espressoPrice]. */
    fun pricing(espressoPrice: Long) {
        val espresso =
            priced(PactValues.ESPRESSO, StorefrontCart.ESPRESSO_SKU, StorefrontCart.ESPRESSO_NAME, espressoPrice)
        val beans =
            priced(PactValues.BEANS, StorefrontCart.BEANS_SKU, StorefrontCart.BEANS_NAME, StorefrontCart.BEANS_PRICE)
        wiremock.stubFor(
            internal(post(urlPathEqualTo("/internal/products/pricing")))
                .willReturn(okJson("""{"items":[$espresso,$beans]}""")),
        )
    }

    /** Ana's owned address (no second line, as the storefront expects) and her contact snapshot. */
    fun identity() {
        wiremock.stubFor(
            internal(get(urlPathEqualTo("/internal/accounts/${PactValues.ADA}/addresses/${PactValues.ADDRESS_OWNED}")))
                .willReturn(
                    okJson(
                        """{"recipientName":"Ada Lovelace","line1":"12 Analytical Street","city":"London",
                        |"region":"England","postalCode":"N1 9GU","countryCode":"GB"}
                        """.trimMargin(),
                    ),
                ),
        )
        wiremock.stubFor(
            internal(get(urlPathEqualTo("/internal/accounts/${PactValues.ADA}/contact"))).willReturn(
                okJson(
                    """{"accountId":"${PactValues.ADA}","email":"ana@example.com","phoneVerified":false,
                    |"channels":["email"],"anonymised":false}
                    """.trimMargin(),
                ),
            ),
        )
    }

    /** `POST /internal/reservations` grants the reservation. */
    fun reserve() {
        wiremock.stubFor(
            internal(post(urlPathEqualTo("/internal/reservations"))).willReturn(
                aResponse()
                    .withStatus(CREATED)
                    .withHeader("Content-Type", JSON)
                    .withBody(
                        """{"reservationId":"${PactValues.RESERVATION}","orderId":"${UUID.randomUUID()}",
                        |"state":"reserved","lines":[],"expiresAt":"2026-10-02T11:00:00Z"}
                        """.trimMargin(),
                    ),
            ),
        )
    }

    /** The reservation is refused: the coffee beans are sold out. Registered after [reserve], so it wins. */
    fun refuseBeans() {
        wiremock.stubFor(
            internal(post(urlPathEqualTo("/internal/reservations"))).willReturn(
                aResponse()
                    .withStatus(CONFLICT)
                    .withHeader("Content-Type", PROBLEM_JSON)
                    .withBody(
                        """{"type":"https://ecommerce.example/problems/insufficient-stock","title":"Insufficient stock",
                        |"status":409,"detail":"No stock.","correlationId":"c","unavailableLines":[{"productId":
                        |"${PactValues.BEANS}","requested":${StorefrontCart.BEANS_QUANTITY},"available":0}]}
                        """.trimMargin(),
                    ),
            ),
        )
    }

    /** Commit and release of any reservation. */
    fun transitions() {
        listOf("commit", "release").forEach { action ->
            wiremock.stubFor(
                internal(post(urlPathMatching("/internal/reservations/[^/]+/$action")))
                    .willReturn(aResponse().withStatus(NO_CONTENT)),
            )
        }
    }

    /**
     * `POST /internal/charges`: approved for [StorefrontCart.APPROVE_TOKEN], declined `card_rejected` for every token
     * starting [StorefrontCart.DECLINE_TOKEN_PREFIX], and no answer (503) for [StorefrontCart.UNREACHABLE_TOKEN], so
     * the payment stays pending without an attempt; each answer takes [delayMillis].
     */
    fun charges(delayMillis: Int = 0) {
        wiremock.stubFor(
            charge(equalTo(StorefrontCart.APPROVE_TOKEN)).willReturn(
                attempt(""""outcome":"approved","providerReference":"sim_ch_000123"""", delayMillis),
            ),
        )
        wiremock.stubFor(
            charge(matching("${StorefrontCart.DECLINE_TOKEN_PREFIX}.*")).willReturn(
                attempt(
                    """"outcome":"declined","declineCategory":"card_rejected","providerReference":"sim_ch_000124"""",
                    delayMillis,
                ),
            ),
        )
        wiremock.stubFor(
            charge(equalTo(StorefrontCart.UNREACHABLE_TOKEN))
                .willReturn(aResponse().withStatus(UNAVAILABLE).withFixedDelay(delayMillis)),
        )
    }

    private fun charge(token: StringValuePattern): MappingBuilder =
        internal(post(urlPathEqualTo("/internal/charges")))
            .withRequestBody(matchingJsonPath("$.paymentMethodRef", token))

    private fun attempt(
        outcome: String,
        delayMillis: Int,
    ): ResponseDefinitionBuilder =
        aResponse()
            .withStatus(CREATED)
            .withFixedDelay(delayMillis)
            .withHeader("Content-Type", JSON)
            .withBody(
                """{"attemptId":"${PactValues.ATTEMPT_APPROVED}","orderId":"${UUID.randomUUID()}","kind":"charge",
                |$outcome,"createdAt":"2026-10-02T10:15:01Z"}
                """.trimMargin(),
            )

    @Suppress("LongParameterList") // the members of one cart line
    private fun cartLine(
        lineId: String,
        productId: String,
        sku: String,
        name: String,
        quantity: Int,
        priceAtAdd: Long,
    ): String =
        """{"lineId":"$lineId","productId":"$productId","sku":"$sku","name":"$name","quantity":$quantity,""" +
            """"priceAtAdd":{"amountMinor":$priceAtAdd,"currency":"BRL"}}"""

    private fun priced(
        productId: String,
        sku: String,
        name: String,
        price: Long,
    ): String =
        """{"productId":"$productId","sku":"$sku","name":"$name","price":{"amountMinor":$price,"currency":"BRL"},""" +
            """"available":${StorefrontCart.AVAILABLE},"saleState":"active"}"""

    private fun internal(builder: MappingBuilder): MappingBuilder =
        builder.withHeader(InternalToken.HEADER, equalTo(InternalToken.TEST))
}
