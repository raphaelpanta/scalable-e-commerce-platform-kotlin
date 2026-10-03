package com.ecommerce.cart.infrastructure

import com.ecommerce.platform.core.problem.ProblemType
import com.ecommerce.platform.messaging.envelope.EventType
import com.ecommerce.platform.messaging.envelope.Topic
import com.ecommerce.platform.messaging.testing.RecordedEvents
import com.ecommerce.platform.testing.ProblemAssertions.expectProblem
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import java.time.Duration
import java.util.UUID

private const val CART_TOKEN = "X-Cart-Token"
private const val BAD_REQUEST = 400
private const val TEAPOT_STOCK = 3
private const val TWO_DEVICE_TEAPOT_STOCK = 5
private const val DEVICE_A_TEAPOTS = 2
private const val DEVICE_B_TEAPOTS = 4
private val EVENT_TIMEOUT: Duration = Duration.ofSeconds(30)

/**
 * `mergeCart`: summing and capping, consuming the anonymous cart (a second merge is 404) and `CartMerged` (T040); two
 * devices' anonymous carts merged one after the other into the same account cart (edge case "two devices", T163).
 */
class CartMergeIT(
    @Autowired private val recorded: RecordedEvents,
) : CartIntegrationTest() {
    private fun add(
        productId: UUID,
        quantity: Int,
        token: String? = null,
        authorization: String? = null,
    ): String? =
        client
            .post()
            .uri("/api/v1/cart/lines")
            .headers { headers ->
                token?.let { headers.set(CART_TOKEN, it) }
                authorization?.let { headers.set(HttpHeaders.AUTHORIZATION, it) }
            }.bodyValue(mapOf("productId" to productId, "quantity" to quantity))
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody(Void::class.java)
            .returnResult()
            .responseHeaders
            .getFirst(CART_TOKEN)

    private fun merge(
        authorization: String?,
        token: String?,
    ) = client
        .post()
        .uri("/api/v1/cart/merge")
        .headers { headers ->
            token?.let { headers.set(CART_TOKEN, it) }
            authorization?.let { headers.set(HttpHeaders.AUTHORIZATION, it) }
        }.exchange()

    @Suppress("UNCHECKED_CAST")
    private fun Json.lines(): List<Json> = this["lines"] as List<Json>

    private fun shouldHaveAnnouncedTheMerge(
        cartId: UUID,
        account: UUID,
    ) {
        val event = recorded.awaitType(EventType.CartMerged) { it.aggregateId == cartId }
        event.producer shouldBe "cart"
        event.payload["accountId"].asString() shouldBe account.toString()
        event.payload["mergedCartCount"].asInt() shouldBe 1
        event.payload["lineCount"].asInt() shouldBe 2
        event.payload["cappedLines"][0]["appliedQuantity"].asInt() shouldBe TEAPOT_STOCK
        recorded.on(Topic.CART).map { it.key } shouldContainExactlyInAnyOrder
            recorded.on(Topic.CART).map { it.envelope.aggregateId.toString() }
    }

    @Test
    fun `signing in merges the anonymous cart capped at the stock, consumes it and announces it`() {
        val account = UUID.randomUUID()
        val teapot = catalog.product(available = TEAPOT_STOCK, sku = "TEA-POT-1", name = "Teapot")
        val coffee = catalog.product()
        add(teapot, 2, authorization = bearer(account))
        val token = add(teapot, 2).shouldNotBeNull()
        add(coffee, 1, token)

        val result =
            merge(bearer(account), token)
                .expectStatus()
                .isOk
                .expectBody(JSON_OBJECT)
                .returnResult()
                .responseBody
                .shouldNotBeNull()

        @Suppress("UNCHECKED_CAST")
        val cart = result["cart"] as Json
        cart.lines().associate { it["productId"] to it["quantity"] } shouldBe
            mapOf(teapot.toString() to TEAPOT_STOCK, coffee.toString() to 1)
        result["cappedLines"] shouldBe
            listOf(
                mapOf(
                    "productId" to teapot.toString(),
                    "requestedQuantity" to TEAPOT_STOCK + 1,
                    "appliedQuantity" to TEAPOT_STOCK,
                ),
            )
        shouldHaveAnnouncedTheMerge(UUID.fromString(cart["id"] as String), account)

        merge(bearer(account), token).expectProblem(ProblemType.NOT_FOUND)
        client
            .get()
            .uri("/api/v1/cart")
            .header(CART_TOKEN, token)
            .exchange()
            .expectProblem(ProblemType.NOT_FOUND)
    }

    @Test
    fun `two devices merge their anonymous carts one after the other, quantities summed and capped at the stock`() {
        val account = UUID.randomUUID()
        val teapot = catalog.product(available = TWO_DEVICE_TEAPOT_STOCK, sku = "TEA-POT-2", name = "Teapot")
        val coffee = catalog.product()
        val mug = catalog.product()
        val deviceA = add(teapot, DEVICE_A_TEAPOTS).shouldNotBeNull()
        add(coffee, 1, deviceA)
        val deviceB = add(teapot, DEVICE_B_TEAPOTS).shouldNotBeNull()
        add(mug, 1, deviceB)

        val first = mergeOk(bearer(account), deviceA)
        first.cart.quantities() shouldBe mapOf(teapot.toString() to DEVICE_A_TEAPOTS, coffee.toString() to 1)
        first.capped shouldBe emptyList<Json>()

        val second = mergeOk(bearer(account), deviceB)
        second.cart["id"] shouldBe first.cart["id"]
        second.cart.quantities() shouldBe
            mapOf(teapot.toString() to TWO_DEVICE_TEAPOT_STOCK, coffee.toString() to 1, mug.toString() to 1)
        second.capped shouldBe
            listOf(
                mapOf(
                    "productId" to teapot.toString(),
                    "requestedQuantity" to DEVICE_A_TEAPOTS + DEVICE_B_TEAPOTS,
                    "appliedQuantity" to TWO_DEVICE_TEAPOT_STOCK,
                ),
            )

        mergedLineCounts(UUID.fromString(second.cart["id"] as String)) shouldBe listOf(2, second.cart.lines().size)
        listOf(deviceA, deviceB).forEach { token -> merge(bearer(account), token).expectProblem(ProblemType.NOT_FOUND) }
        accountCart(account).quantities() shouldBe second.cart.quantities()
    }

    private fun Json.quantities(): Map<Any?, Any?> = lines().associate { it["productId"] to it["quantity"] }

    /** `lineCount` of the two `CartMerged` events of [cartId], in publication order. */
    private fun mergedLineCounts(cartId: UUID): List<Int> {
        await().atMost(EVENT_TIMEOUT).until {
            recorded.on(Topic.CART).count { it.envelope.aggregateId == cartId } == 2
        }
        return recorded
            .on(Topic.CART)
            .filter { it.envelope.aggregateId == cartId }
            .map { it.envelope.payload["lineCount"].asInt() }
    }

    private fun accountCart(account: UUID): Json =
        client
            .get()
            .uri("/api/v1/cart")
            .header(HttpHeaders.AUTHORIZATION, bearer(account))
            .exchange()
            .expectStatus()
            .isOk
            .expectBody(JSON_OBJECT)
            .returnResult()
            .responseBody
            .shouldNotBeNull()

    /** The cart and capped lines of a successful merge. */
    private data class Merged(
        val cart: Json,
        val capped: List<*>,
    )

    @Suppress("UNCHECKED_CAST")
    private fun mergeOk(
        authorization: String,
        token: String,
    ): Merged {
        val body =
            merge(authorization, token)
                .expectStatus()
                .isOk
                .expectBody(JSON_OBJECT)
                .returnResult()
                .responseBody
                .shouldNotBeNull()
        return Merged(body["cart"] as Json, body["cappedLines"] as List<*>)
    }

    @Test
    fun `merging needs a bearer token and the anonymous cart token`() {
        val token = add(catalog.product(), 1).shouldNotBeNull()

        merge(null, token).expectProblem(ProblemType.UNAUTHORIZED)
        merge(bearer(UUID.randomUUID()), null).expectProblem(ProblemType.VALIDATION, BAD_REQUEST)
        merge("Bearer " + jwt.tokenFor(UUID.randomUUID(), emptySet()), token).expectProblem(ProblemType.FORBIDDEN)
    }
}
