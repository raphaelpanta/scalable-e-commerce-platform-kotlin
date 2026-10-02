package com.ecommerce.catalog.infrastructure

import com.ecommerce.catalog.infrastructure.jobs.ReservationExpiryJob
import com.ecommerce.platform.core.problem.ProblemType
import com.ecommerce.platform.messaging.envelope.EventType
import com.ecommerce.platform.messaging.testing.RecordedEvents
import com.ecommerce.platform.testing.ProblemAssertions.expectProblem
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import kotlinx.coroutines.reactor.mono
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

private const val OK = 200
private const val CREATED = 201
private const val NO_CONTENT = 204
private const val BAD_REQUEST = 400
private const val CONFLICT = 409
private const val RACERS = 8
private const val ONE_UNIT = 1
private const val TWO_UNITS = 2
private const val STOCK = 5
private const val PRICE = 9490
private val JOB_TIMEOUT: Duration = Duration.ofSeconds(10)

/**
 * The internal reservation and pricing API (catalog-internal.yaml) against PostgreSQL and Kafka: the token guard,
 * all-or-nothing reservations, idempotent commit and release, the race for the last unit, the expiry job and the
 * stock events relayed to `catalog.stock.v1`.
 */
class ReservationIT(
    @Autowired private val recorded: RecordedEvents,
    @Autowired private val expiryJob: ReservationExpiryJob,
) : CatalogIntegrationTest() {
    private fun reserve(
        orderId: UUID,
        vararg lines: Pair<String, Int>,
    ) = internal(
        HttpMethod.POST,
        "/internal/reservations",
        mapOf(
            "orderId" to orderId,
            "lines" to lines.map { (id, quantity) -> mapOf("productId" to id, "quantity" to quantity) },
        ),
    )

    @Test
    fun `internal endpoints refuse a missing or wrong token with 401`() {
        val productId = product()
        internal(
            HttpMethod.GET,
            "/internal/products/$productId/pricing",
            token = null,
        ).expectProblem(ProblemType.UNAUTHORIZED)
        internal(
            HttpMethod.GET,
            "/internal/products/$productId/pricing",
            token = "wrong",
        ).expectProblem(ProblemType.UNAUTHORIZED)
        internal(HttpMethod.POST, "/internal/reservations", mapOf("orderId" to UUID.randomUUID()), token = null)
            .expectProblem(ProblemType.UNAUTHORIZED)
        internal(HttpMethod.GET, "/internal/products/$productId/pricing").json(OK)["productId"] shouldBe productId
    }

    @Test
    fun `a reservation holds every line at once, is replayed for the same order and publishes StockReserved`() {
        val espresso = product(stock = STOCK)
        val beans = product(stock = STOCK)
        val orderId = UUID.randomUUID()

        val created = reserve(orderId, espresso to ONE_UNIT, beans to TWO_UNITS).json(CREATED)
        val replay = reserve(orderId, espresso to STOCK).json(OK)

        created["state"] shouldBe "reserved"
        created["orderId"] shouldBe orderId.toString()
        created["lines"] shouldBe
            listOf(
                mapOf("productId" to espresso, "quantity" to ONE_UNIT),
                mapOf(
                    "productId" to beans,
                    "quantity" to TWO_UNITS,
                ),
            )
        created["expiresAt"].toString() shouldMatch Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z")
        replay shouldBe created
        stockOf(espresso) shouldBe "5/1"
        stockOf(beans) shouldBe "5/2"
        val event =
            recorded.awaitType(
                EventType.StockReserved,
            ) { it.aggregateId.toString() == created["reservationId"] }
        event.payload["orderId"].asString() shouldBe orderId.toString()
        event.payload["expiresAt"].asString() shouldBe created["expiresAt"]
        event.producer shouldBe "catalog"
    }

    @Test
    fun `a shortage refuses the whole reservation and names every short line, unknown and withdrawn as 0`() {
        val plenty = product(stock = STOCK)
        val scarce = product(stock = ONE_UNIT)
        val withdrawn = product(stock = STOCK)
        call(HttpMethod.POST, "$PRODUCTS/$withdrawn/withdrawal", operator()).expectStatus().isOk
        val unknown = UUID.randomUUID().toString()

        val refused =
            reserve(
                UUID.randomUUID(),
                plenty to ONE_UNIT,
                scarce to TWO_UNITS,
                withdrawn to ONE_UNIT,
                unknown to ONE_UNIT,
            ).expectProblem(ProblemType.INSUFFICIENT_STOCK)

        refused["detail"] shouldBe "One or more products cannot be reserved in the requested quantity."
        refused["instance"] shouldBe "/internal/reservations"
        refused["unavailableLines"] shouldBe
            listOf(
                mapOf("productId" to scarce, "requested" to TWO_UNITS, "available" to ONE_UNIT),
                mapOf("productId" to withdrawn, "requested" to ONE_UNIT, "available" to 0),
                mapOf("productId" to unknown, "requested" to ONE_UNIT, "available" to 0),
            )
        stockOf(plenty) shouldBe "5/0"
    }

    @Test
    fun `malformed reservations are refused with 400`() {
        val productId = product()
        reserve(UUID.randomUUID(), productId to 0).expectProblem(ProblemType.VALIDATION, BAD_REQUEST)
        reserve(
            UUID.randomUUID(),
            productId to ONE_UNIT,
            productId to ONE_UNIT,
        ).expectProblem(ProblemType.VALIDATION, BAD_REQUEST)
        reserve(UUID.randomUUID()).expectProblem(ProblemType.VALIDATION, BAD_REQUEST)
        internal(HttpMethod.POST, "/internal/reservations", mapOf("lines" to emptyList<Any>()))
            .expectProblem(ProblemType.VALIDATION, BAD_REQUEST)
        internal(
            HttpMethod.POST,
            "/internal/reservations/not-a-uuid/commit",
        ).expectProblem(ProblemType.VALIDATION, BAD_REQUEST)
    }

    @Test
    fun `of parallel reservations of the last unit exactly one succeeds and stock never becomes negative`() {
        val lastUnit = product(stock = ONE_UNIT)
        val pool = Executors.newFixedThreadPool(RACERS)
        val statuses =
            try {
                pool
                    .invokeAll(
                        (1..RACERS).map {
                            Callable {
                                reserve(
                                    UUID.randomUUID(),
                                    lastUnit to ONE_UNIT,
                                ).returnResult(String::class.java).status.value()
                            }
                        },
                    ).map { it.get() }
            } finally {
                pool.shutdown()
            }

        statuses.count { it == CREATED } shouldBe 1
        statuses.count { it == CONFLICT } shouldBe RACERS - 1
        stockOf(lastUnit) shouldBe "1/1"
    }

    @Test
    fun `commit and release are idempotent, refused across each other and 404 when unknown`() {
        val productId = product(stock = STOCK)
        val committed = reserve(UUID.randomUUID(), productId to TWO_UNITS).json(CREATED)["reservationId"]
        val released = reserve(UUID.randomUUID(), productId to ONE_UNIT).json(CREATED)["reservationId"]

        repeat(
            2,
        ) { internal(HttpMethod.POST, "/internal/reservations/$committed/commit").expectStatus().isEqualTo(NO_CONTENT) }
        repeat(
            2,
        ) { internal(HttpMethod.POST, "/internal/reservations/$released/release").expectStatus().isEqualTo(NO_CONTENT) }

        stockOf(productId) shouldBe "3/0"
        internal(HttpMethod.POST, "/internal/reservations/$committed/release").expectProblem(ProblemType.CONFLICT)
        internal(HttpMethod.POST, "/internal/reservations/$released/commit").expectProblem(ProblemType.CONFLICT)
        internal(
            HttpMethod.POST,
            "/internal/reservations/${UUID.randomUUID()}/commit",
        ).expectProblem(ProblemType.NOT_FOUND)
        internal(
            HttpMethod.POST,
            "/internal/reservations/${UUID.randomUUID()}/release",
        ).expectProblem(ProblemType.NOT_FOUND)
        recorded.awaitType(EventType.StockCommitted) { it.aggregateId.toString() == committed }
        val release = recorded.awaitType(EventType.StockReservationReleased) { it.aggregateId.toString() == released }
        release.payload["reason"].asString() shouldBe "CANCELLED"
        release.payload["restocked"].asBoolean() shouldBe false
        recorded.ofType(EventType.StockCommitted).filter { it.aggregateId.toString() == committed } shouldHaveSize 1
    }

    @Test
    fun `the expiry job releases reservations still reserved after their expiry`() {
        val productId = product(stock = STOCK)
        val expired = reserve(UUID.randomUUID(), productId to TWO_UNITS).json(CREATED)["reservationId"].toString()
        val open = reserve(UUID.randomUUID(), productId to ONE_UNIT).json(CREATED)["reservationId"].toString()
        execute(
            "UPDATE reservations SET created_at = :created, expires_at = :expires WHERE id = :id",
            mapOf(
                "created" to Instant.now().minus(Duration.ofHours(1)),
                "expires" to Instant.now().minusSeconds(1),
                "id" to UUID.fromString(expired),
            ),
        )

        mono { expiryJob.expireNow() }.block(JOB_TIMEOUT)

        stockOf(productId) shouldBe "5/1"
        val event = recorded.awaitType(EventType.StockReservationReleased) { it.aggregateId.toString() == expired }
        event.payload["reason"].asString() shouldBe "EXPIRED"
        internal(HttpMethod.POST, "/internal/reservations/$open/commit").expectStatus().isEqualTo(NO_CONTENT)
    }

    @Test
    fun `pricing answers withdrawn products, omits unknown ones from a batch and 404s an unknown product`() {
        val active =
            product(
                name = "Priced",
                priceMinor = PRICE.toLong(),
                stock = STOCK,
                sku = "PRC-${UUID.randomUUID().toString().take(8).uppercase()}",
            )
        val withdrawn = product(stock = TWO_UNITS)
        call(HttpMethod.POST, "$PRODUCTS/$withdrawn/withdrawal", operator()).expectStatus().isOk
        val unknown = UUID.randomUUID()

        val single = internal(HttpMethod.GET, "/internal/products/$active/pricing").json(OK)
        val batch =
            internal(
                HttpMethod.POST,
                "/internal/products/pricing",
                mapOf("productIds" to listOf(withdrawn, unknown, active)),
            ).json(OK)

        single["name"] shouldBe "Priced"
        single["price"] shouldBe mapOf("amountMinor" to PRICE, "currency" to "BRL")
        single["available"] shouldBe STOCK
        single["saleState"] shouldBe "active"
        @Suppress("UNCHECKED_CAST")
        val items = batch["items"] as List<Json>
        items.map { it["productId"] } shouldContainExactly listOf(withdrawn, active)
        items.first()["saleState"] shouldBe "withdrawn"
        internal(
            HttpMethod.GET,
            "/internal/products/$unknown/pricing",
        ).expectProblem(ProblemType.NOT_FOUND)["detail"] shouldBe
            "Product not found."
        internal(HttpMethod.POST, "/internal/products/pricing", mapOf("productIds" to emptyList<String>()))
            .expectProblem(ProblemType.VALIDATION, BAD_REQUEST)
    }
}
