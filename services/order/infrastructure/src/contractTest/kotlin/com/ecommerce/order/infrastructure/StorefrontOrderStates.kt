package com.ecommerce.order.infrastructure

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junitsupport.State
import com.ecommerce.order.application.OrderRepository
import com.ecommerce.order.infrastructure.StorefrontOrders.ANA
import com.ecommerce.order.infrastructure.StorefrontOrders.ORDER_1
import com.ecommerce.order.infrastructure.StorefrontOrders.ORDER_2
import com.ecommerce.order.infrastructure.StorefrontOrders.ORDER_3
import com.ecommerce.order.infrastructure.StorefrontOrders.ORDER_4
import com.ecommerce.order.infrastructure.StorefrontOrders.ORDER_5
import com.ecommerce.order.infrastructure.StorefrontOrders.OTHER_SHOPPER
import com.ecommerce.platform.testing.InternalToken
import com.ecommerce.platform.testing.JwtFixture
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

private const val CREATED = 201
private const val CONFLICT = 409

/** How long the slow charge of the cancellation race takes to answer, while the order is cancelled. */
private const val SLOW_CHARGE_MILLIS = 2_000

/**
 * Provider states O1 to O11 of feature 005 `contracts/pact-matrix.md` ("Storefront to order"), named verbatim with
 * the full order id the storefront sends. The storefront's requests arrive as the gateway forwards them: a bearer of
 * the signed-in account, which the [BearerRewritingTarget] mints for the caller each state names (ana@example.com,
 * `7c1d4f3e-...`, a shopper, unless the state signs in the operator ops@example.com). Each state empties the schema,
 * seeds through the service's own repository and stubs cart, catalog, identity and payment with WireMock; the
 * `Idempotency-Key key-1` and `cancelled while its payment was being processed` states run a real checkout first,
 * so the verifier's request is the replay of FR-008. Extended by [OrderProviderStates]; abstract because JUnit runs
 * only the concrete verification classes. One method per provider state of the matrix.
 */
@Suppress("TooManyFunctions", "AbstractClassCanBeConcreteClass")
abstract class StorefrontOrderStates {
    @LocalServerPort
    protected var port: Int = 0

    @Autowired
    protected lateinit var database: DatabaseClient

    @Autowired
    protected lateinit var orders: OrderRepository

    private var caller: StorefrontCaller = StorefrontCaller.ANA
    private val stubs = StorefrontStubs(dependencies)
    private val harness: StorefrontHarness by lazy {
        StorefrontHarness(
            database,
            orders,
            jwt,
            "http://localhost:$port",
        )
    }

    @BeforeEach
    fun signInAna() {
        caller = StorefrontCaller.ANA
    }

    /** The HTTP target: the service on its random port, replayed requests carrying the bearer of the current caller. */
    protected fun httpTarget(): HttpTestTarget = BearerRewritingTarget(port) { harness.bearerOf(caller) }

    @State(
        "ana@example.com has a cart at revision rev-7f3a9c21 with stock available and owns address " +
            "5f0c1a52-3a43-4a53-9f58-7a1d8b9d2c11",
    )
    fun cartReadyForCheckout() {
        fresh()
        stubs.checkout()
    }

    @State("ana@example.com already placed an order with Idempotency-Key key-1")
    fun orderPlacedWithKey1() {
        fresh()
        stubs.checkout()
        val status = harness.checkout(PactValues.KEY_1)
        check(status == CREATED) { "the checkout with key-1 answered $status" }
    }

    @State("the price of a line changed after revision rev-7f3a9c21")
    fun priceChangedAfterRevision() {
        fresh()
        stubs.checkout(revision = StorefrontCart.CURRENT_REVISION, espressoPrice = StorefrontCart.ESPRESSO_NEW_PRICE)
    }

    @State("a line in the cart of ana@example.com exceeds available stock")
    fun lineExceedsStock() {
        fresh()
        stubs.checkout()
        stubs.refuseBeans()
    }

    @State("an order of ana@example.com was cancelled while its payment was being processed")
    fun orderCancelledWhileCharging() {
        fresh()
        stubs.checkout(chargeDelayMillis = SLOW_CHARGE_MILLIS)
        val status = harness.checkoutCancelledWhileCharging(PactValues.KEY_1)
        check(status == CONFLICT) { "the checkout cancelled during its charge answered $status" }
    }

    @State("payment is declined for token tok_sim_decline_01")
    fun paymentDeclined() {
        fresh()
        stubs.checkout()
    }

    @State("payment is pending for token tok_sim_unreachable")
    fun paymentPending() {
        fresh()
        stubs.checkout()
    }

    @State("ana@example.com has 3 orders")
    fun anaHasThreeOrders() {
        fresh()
        harness.store(
            StorefrontOrders.paid(ORDER_1, sequence = 1),
            StorefrontOrders.paid(ORDER_2, sequence = 2),
            StorefrontOrders.paid(ORDER_3, sequence = THREE),
        )
    }

    @State("ana@example.com has no orders")
    fun anaHasNoOrders() {
        fresh()
    }

    @State("ana@example.com owns order 0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10")
    fun anaOwnsOrder1() {
        fresh()
        harness.store(StorefrontOrders.paid())
    }

    @State("the payment of order 0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10 is pending and becomes approved")
    fun paymentOfOrder1Pending() {
        fresh()
        harness.store(StorefrontOrders.pending())
    }

    @State("order 0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10 belongs to another shopper")
    fun order1BelongsToAnotherShopper() {
        fresh()
        harness.store(StorefrontOrders.paid(owner = OTHER_SHOPPER))
    }

    @State("ana@example.com owns a placed order 0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10")
    fun anaOwnsPlacedOrder1() {
        fresh()
        harness.store(StorefrontOrders.paid())
    }

    @State("ana@example.com owns a shipped order 0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10")
    fun anaOwnsShippedOrder1() {
        fresh()
        harness.store(StorefrontOrders.shipped())
    }

    @State("an operator ops@example.com is signed in and 5 orders of 2 shoppers exist")
    fun operatorSignedInWithFiveOrders() {
        fresh()
        caller = StorefrontCaller.OPERATOR
        harness.store(
            StorefrontOrders.paid(ORDER_1, ANA, 1),
            StorefrontOrders.paid(ORDER_2, ANA, 2),
            StorefrontOrders.paid(ORDER_3, ANA, THREE),
            StorefrontOrders.paid(ORDER_4, OTHER_SHOPPER, FOUR),
            StorefrontOrders.paid(ORDER_5, OTHER_SHOPPER, FIVE),
        )
    }

    @State("an operator ops@example.com is signed in and order 0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10 exists")
    fun operatorSignedInWithOrder1() {
        fresh()
        caller = StorefrontCaller.OPERATOR
        harness.store(StorefrontOrders.paid())
    }

    @State("order 0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10 is placed with payment approved")
    fun order1PlacedWithPaymentApproved() {
        fresh()
        caller = StorefrontCaller.OPERATOR
        harness.store(StorefrontOrders.paid())
    }

    @State("order 0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10 is placed with payment pending")
    fun order1PlacedWithPaymentPending() {
        fresh()
        caller = StorefrontCaller.OPERATOR
        harness.store(StorefrontOrders.pending())
    }

    @State("order 0b9a3b0e-62b7-4f55-8d7e-0c3a6d1d9a10 is delivered")
    fun order1Delivered() {
        fresh()
        caller = StorefrontCaller.OPERATOR
        harness.store(StorefrontOrders.delivered())
    }

    @State("a shopper ana@example.com is signed in")
    fun shopperSignedIn() {
        fresh()
    }

    /** No stubs, no orders: the state describes everything its interaction needs. */
    private fun fresh() {
        stubs.reset()
        harness.reset()
    }

    companion object {
        private const val THREE = 3L
        private const val FOUR = 4L
        private const val FIVE = 5L

        /** WireMock standing in for cart, catalog, payment and identity (one server, their paths do not overlap). */
        val dependencies: WireMockServer = WireMockServer(options().dynamicPort()).apply { start() }

        /** Signs the bearers of the replayed requests and serves the JWKS the service validates them with. */
        val jwt: JwtFixture = JwtFixture().also { it.startJwks() }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("platform.security.jwks-uri") { jwt.jwksUri }
            registry.add("platform.security.internal-token") { InternalToken.TEST }
            listOf("cart-url", "catalog-url", "payment-url", "identity-url").forEach { name ->
                registry.add("order.clients.$name") { dependencies.baseUrl() }
            }
        }
    }
}
