package com.ecommerce.order.infrastructure

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.MappingBuilder
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.matching
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.stubbing.Scenario
import java.util.UUID

private const val CREATED = 201
private const val NO_CONTENT = 204
private const val CONFLICT = 409
private const val INTERNAL_TOKEN_HEADER = "X-Internal-Token"
private const val PROBLEM_JSON = "application/problem+json"

const val APPROVED_TOKEN = "tok_sim_approve_4242"
const val DECLINED_TOKEN = "tok_sim_decline_0001"
const val UNREACHABLE_TOKEN = "tok_sim_unreachable"

/** One shopper with a one-line cart, a saved address and the catalogue product of that line. */
data class Shopper(
    val accountId: UUID = UUID.randomUUID(),
    val addressId: UUID = UUID.randomUUID(),
    val productId: UUID = UUID.randomUUID(),
    val reservationId: UUID = UUID.randomUUID(),
    val revision: String = "rev-" + UUID.randomUUID(),
    val priceMinor: Long = 14_900,
    val quantity: Int = 1,
)

/** WireMock stand-ins of cart, catalog, payment and identity, keyed by account and product ids. */
@Suppress("TooManyFunctions") // one stub per internal endpoint the checkout calls
class Stubs(
    private val wiremock: WireMockServer,
) {
    /** Stubs everything a successful checkout of [shopper] reads; the charge outcome follows the card token. */
    fun checkoutOf(
        shopper: Shopper,
        priceAtAdd: Long = shopper.priceMinor,
    ): Shopper {
        cart(shopper, priceAtAdd)
        identity(shopper)
        pricing(shopper)
        reserve(shopper)
        transitions()
        charges()
        return shopper
    }

    fun cart(
        shopper: Shopper,
        priceAtAdd: Long = shopper.priceMinor,
    ) {
        wiremock.stubFor(
            internal(get(urlPathEqualTo("/internal/carts/by-account/${shopper.accountId}"))).willReturn(
                okJson(
                    """{"cartId":"${UUID.randomUUID()}","revision":"${shopper.revision}","lines":[{"lineId":"${UUID.randomUUID()}",
                    |"productId":"${shopper.productId}","sku":"SKU-1","name":"Espresso Machine","quantity":${shopper.quantity},
                    |"priceAtAdd":{"amountMinor":$priceAtAdd,"currency":"BRL"}}],
                    |"total":{"amountMinor":${priceAtAdd * shopper.quantity},"currency":"BRL"}}
                    """.trimMargin(),
                ),
            ),
        )
        wiremock.stubFor(
            internal(post(urlPathEqualTo("/internal/carts/by-account/${shopper.accountId}/clear")))
                .willReturn(aResponse().withStatus(NO_CONTENT)),
        )
    }

    fun identity(shopper: Shopper) {
        wiremock.stubFor(
            internal(get(urlPathEqualTo("/internal/accounts/${shopper.accountId}/addresses/${shopper.addressId}")))
                .willReturn(
                    okJson(
                        """{"recipientName":"Ada Lovelace","line1":"12 Analytical Street","line2":"Flat 2",
                        |"city":"London","region":"England","postalCode":"N1 9GU","countryCode":"GB"}
                        """.trimMargin(),
                    ),
                ),
        )
        wiremock.stubFor(
            internal(get(urlPathEqualTo("/internal/accounts/${shopper.accountId}/contact"))).willReturn(
                okJson(
                    """{"accountId":"${shopper.accountId}","email":"ada@example.test","phoneVerified":false,
                    |"channels":["email"],"anonymised":false}
                    """.trimMargin(),
                ),
            ),
        )
    }

    fun pricing(shopper: Shopper) {
        wiremock.stubFor(
            internal(post(urlPathEqualTo("/internal/products/pricing")))
                .withRequestBody(matchingJsonPath("$.productIds[0]", equalTo(shopper.productId.toString())))
                .willReturn(
                    okJson(
                        """{"items":[{"productId":"${shopper.productId}","sku":"SKU-1","name":"Espresso Machine",
                        |"price":{"amountMinor":${shopper.priceMinor},"currency":"BRL"},"available":5,"saleState":"active"}]}
                        """.trimMargin(),
                    ),
                ),
        )
    }

    fun reserve(shopper: Shopper) {
        wiremock.stubFor(reservationOf(shopper).willReturn(reserved(shopper)))
    }

    /** The first reservation of [productId] succeeds, every later one is refused: the last unit. */
    fun lastUnit(
        productId: UUID,
        shoppers: List<Shopper>,
    ) {
        val scenario = "last-unit-$productId"
        shoppers.forEach { shopper ->
            wiremock.stubFor(
                reservationOf(shopper)
                    .inScenario(scenario)
                    .whenScenarioStateIs(Scenario.STARTED)
                    .willSetStateTo("sold-out")
                    .willReturn(reserved(shopper)),
            )
            wiremock.stubFor(
                reservationOf(shopper)
                    .inScenario(scenario)
                    .whenScenarioStateIs("sold-out")
                    .willReturn(
                        aResponse()
                            .withStatus(CONFLICT)
                            .withHeader("Content-Type", PROBLEM_JSON)
                            .withBody(
                                """{"type":"https://ecommerce.example/problems/insufficient-stock",
                                |"title":"Insufficient stock","status":409,"detail":"No stock.",
                                |"correlationId":"c","unavailableLines":[{"productId":"$productId","requested":1,"available":0}]}
                                """.trimMargin(),
                            ),
                    ),
            )
        }
    }

    fun refuseReservation(shopper: Shopper) {
        wiremock.stubFor(
            reservationOf(shopper).willReturn(
                aResponse()
                    .withStatus(CONFLICT)
                    .withHeader("Content-Type", PROBLEM_JSON)
                    .withBody(
                        """{"type":"https://ecommerce.example/problems/insufficient-stock","title":"Insufficient stock",
                        |"status":409,"detail":"No stock.","correlationId":"c",
                        |"unavailableLines":[{"productId":"${shopper.productId}","requested":${shopper.quantity},"available":0}]}
                        """.trimMargin(),
                    ),
            ),
        )
    }

    fun transitions() {
        listOf("commit", "release").forEach { action ->
            wiremock.stubFor(
                internal(post(urlPathMatching("/internal/reservations/[^/]+/$action")))
                    .willReturn(aResponse().withStatus(NO_CONTENT)),
            )
        }
    }

    fun charges() {
        charge(APPROVED_TOKEN, """"outcome":"approved","providerReference":"sim_ch_1"""")
        charge(
            DECLINED_TOKEN,
            """"outcome":"declined","declineCategory":"card_rejected","providerReference":"sim_ch_2"""",
        )
        charge(UNREACHABLE_TOKEN, """"outcome":"pending"""")
    }

    /** How many times [action] was called for the reservation of [shopper]. */
    fun reservationCalls(
        shopper: Shopper,
        action: String,
    ): Int =
        wiremock
            .findAll(postRequestedFor(urlPathEqualTo("/internal/reservations/${shopper.reservationId}/$action")))
            .size

    fun clearCalls(shopper: Shopper): Int =
        wiremock.findAll(postRequestedFor(urlPathEqualTo("/internal/carts/by-account/${shopper.accountId}/clear"))).size

    fun chargeCalls(shopper: Shopper): Int =
        wiremock
            .findAll(
                postRequestedFor(urlPathEqualTo("/internal/charges"))
                    .withRequestBody(matchingJsonPath("$.accountId", equalTo(shopper.accountId.toString()))),
            ).size

    private fun charge(
        token: String,
        outcome: String,
    ) {
        wiremock.stubFor(
            internal(post(urlPathEqualTo("/internal/charges")))
                .withRequestBody(matchingJsonPath("$.paymentMethodRef", equalTo(token)))
                .willReturn(
                    aResponse()
                        .withStatus(CREATED)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """{"attemptId":"${UUID.randomUUID()}","orderId":"${UUID.randomUUID()}","kind":"charge",
                            |$outcome,"createdAt":"2026-10-02T10:15:01Z"}
                            """.trimMargin(),
                        ),
                ),
        )
    }

    private fun reservationOf(shopper: Shopper): MappingBuilder =
        internal(post(urlPathEqualTo("/internal/reservations")))
            .withHeader("X-Correlation-Id", matching(".+"))
            .withRequestBody(matchingJsonPath("$.orderId"))
            .withRequestBody(matchingJsonPath("$.lines[0].productId", equalTo(shopper.productId.toString())))
            .withRequestBody(matchingJsonPath("$.lines[0].quantity", equalTo(shopper.quantity.toString())))

    private fun reserved(shopper: Shopper) =
        aResponse()
            .withStatus(CREATED)
            .withHeader("Content-Type", "application/json")
            .withBody(
                """{"reservationId":"${shopper.reservationId}","orderId":"${UUID.randomUUID()}","state":"reserved",
                |"lines":[{"productId":"${shopper.productId}","quantity":${shopper.quantity}}],"expiresAt":"2026-10-02T11:00:00Z"}
                """.trimMargin(),
            )

    private fun internal(builder: MappingBuilder): MappingBuilder =
        builder.withHeader(INTERNAL_TOKEN_HEADER, equalTo("pact-internal-token"))
}
