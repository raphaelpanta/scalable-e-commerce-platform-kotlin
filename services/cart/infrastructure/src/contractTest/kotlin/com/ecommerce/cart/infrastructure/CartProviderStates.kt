package com.ecommerce.cart.infrastructure

import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.State
import com.ecommerce.platform.testing.InternalToken
import com.ecommerce.platform.testing.KafkaTestConfig
import com.ecommerce.platform.testing.PostgresTestConfig
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val QUERY_TIMEOUT: Duration = Duration.ofSeconds(10)
private val SEEDED_AT: Instant = Instant.parse("2026-10-02T10:00:00Z")
private const val OK = 200
private const val PLENTY = 99

/**
 * Provider side for every consumer of the cart (pact-interactions.md sections 2.2 and 5), against the running service.
 * The provider states seed exactly the carts their parameters describe; the catalogue is a WireMock that prices every
 * seeded line at its price at add, so the service never depends on another one. Shared by
 * [CartProviderVerificationTest] (pacts of `build/pacts`) and [CartBrokerVerificationTest] (pacts of the Pact Broker),
 * which only choose the pact source; both are tagged `provider` and run in `contractVerify`. The storefront's own
 * states (`storefront-cart.json`) are inherited from [StorefrontCartStates], which also owns the catalogue stub.
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = ["management.server.port=", "platform.security.internal-token=" + InternalToken.TEST],
)
@Import(PostgresTestConfig::class, KafkaTestConfig::class)
// Abstract: JUnit runs only the subclasses, which choose the pact source (folder or broker).
@Suppress("AbstractClassCanBeConcreteClass")
abstract class CartProviderStates : StorefrontCartStates() {
    @LocalServerPort
    protected var port: Int = 0

    @BeforeEach
    fun target(context: PactVerificationContext?) {
        context?.target = httpTarget(port)
    }

    @State("the cart service is running")
    fun serviceRunning() {
        // The Spring context and its database are already up; nothing to arrange.
    }

    @State("an account cart exists")
    fun accountCartExists(parameters: Map<String, Any?>) {
        val accountId = uuid(parameters, "accountId")
        val cartId = uuid(parameters, "cartId")
        val lines = (parameters["lines"] as? List<*>).orEmpty().map { it as Map<*, *> }
        seedCart(accountId, cartId)
        lines.forEachIndexed { position, line -> seedLine(cartId, position, line) }
        catalogPrices(lines)
    }

    @State("an empty account cart exists")
    fun emptyAccountCartExists(parameters: Map<String, Any?>) {
        seedCart(uuid(parameters, "accountId"), uuid(parameters, "cartId"))
        catalogPrices(emptyList())
    }

    @State("no cart exists for the account")
    fun noCartExists(parameters: Map<String, Any?>) {
        execute("DELETE FROM cart WHERE account_id = :accountId", mapOf("accountId" to uuid(parameters, "accountId")))
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    fun cartHonoursItsConsumers(context: PactVerificationContext?) {
        context?.verifyInteraction()
    }

    private fun seedCart(
        accountId: UUID,
        cartId: UUID,
    ) {
        execute(
            "DELETE FROM cart WHERE account_id = :accountId OR id = :id",
            mapOf(
                "accountId" to accountId,
                "id" to cartId,
            ),
        )
        execute(
            "INSERT INTO cart (id, account_id, token_hash, version, created_at, updated_at) " +
                "VALUES (:id, :accountId, NULL, 1, :at, :at)",
            mapOf("id" to cartId, "accountId" to accountId, "at" to SEEDED_AT),
        )
    }

    private fun seedLine(
        cartId: UUID,
        position: Int,
        line: Map<*, *>,
    ) {
        val price = line["priceAtAdd"] as Map<*, *>
        execute(
            "INSERT INTO cart_line (id, cart_id, product_id, sku, name, quantity, price_at_add_minor, currency, " +
                "added_at, position) VALUES (:id, :cartId, :productId, :sku, :name, :quantity, :price, :currency, " +
                ":at, :position)",
            mapOf(
                "id" to UUID.fromString(line["lineId"] as String),
                "cartId" to cartId,
                "productId" to UUID.fromString(line["productId"] as String),
                "sku" to line["sku"] as String,
                "name" to line["name"] as String,
                "quantity" to (line["quantity"] as Number).toInt(),
                "price" to (price["amountMinor"] as Number).toLong(),
                "currency" to price["currency"] as String,
                "at" to SEEDED_AT,
                "position" to position,
            ),
        )
    }

    /** The catalogue prices every seeded line at its price at add, with stock to spare. */
    private fun catalogPrices(lines: List<Map<*, *>>) {
        val items =
            lines.map { line ->
                mapOf(
                    "productId" to line["productId"],
                    "sku" to line["sku"],
                    "name" to line["name"],
                    "price" to line["priceAtAdd"],
                    "available" to PLENTY,
                    "saleState" to "active",
                )
            }
        catalog.stubFor(
            post(urlEqualTo("/internal/products/pricing")).willReturn(
                aResponse()
                    .withStatus(OK)
                    .withHeader("Content-Type", "application/json")
                    .withBody(mapper.writeValueAsString(mapOf("items" to items))),
            ),
        )
    }

    private fun execute(
        sql: String,
        bindings: Map<String, Any>,
    ) {
        bindings.entries
            .fold(database.sql(sql)) { spec, (name, value) -> spec.bind(name, value) }
            .fetch()
            .rowsUpdated()
            .block(QUERY_TIMEOUT)
    }

    companion object {
        private fun uuid(
            parameters: Map<String, Any?>,
            name: String,
        ): UUID = UUID.fromString(checkNotNull(parameters[name]) { "provider state parameter $name" }.toString())
    }
}
