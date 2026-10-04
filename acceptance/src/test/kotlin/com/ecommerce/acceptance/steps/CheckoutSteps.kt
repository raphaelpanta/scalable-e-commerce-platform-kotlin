package com.ecommerce.acceptance.steps

import com.ecommerce.acceptance.support.Cards
import com.ecommerce.acceptance.support.CartHolder
import com.ecommerce.acceptance.support.Checkout
import com.ecommerce.acceptance.support.Concurrency
import com.ecommerce.acceptance.support.Environment
import com.ecommerce.acceptance.support.Headers
import com.ecommerce.acceptance.support.ScenarioWorld
import com.ecommerce.acceptance.support.Shopper
import com.ecommerce.acceptance.support.Status
import com.ecommerce.acceptance.support.eventually
import com.ecommerce.acceptance.support.items
import com.ecommerce.acceptance.support.list
import com.ecommerce.acceptance.support.minor
import com.ecommerce.acceptance.support.requireString
import com.ecommerce.acceptance.support.shouldBeProblem
import com.ecommerce.acceptance.support.shouldHaveStatus
import com.ecommerce.acceptance.support.string
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import tools.jackson.databind.JsonNode
import java.time.Duration

/** Checkout and payment (user story 4, order.yaml and payment.yaml). */
class CheckoutSteps(
    private val world: ScenarioWorld,
) {
    private var raceAnswers: List<Checkout> = emptyList()

    @When("the shopper checks out paying with a card the simulator approves")
    fun checksOutApproved() {
        checkOut(Cards.APPROVED)
    }

    @When("the shopper checks out paying with a card the simulator declines")
    fun checksOutDeclined() {
        checkOut(Cards.DECLINED)
    }

    @When("the shopper checks out paying with a card while the payment provider is unreachable")
    fun checksOutUnreachable() {
        checkOut(Cards.UNREACHABLE)
    }

    @When("the shopper checks out with the cart as last viewed paying with a card the simulator approves")
    fun checksOutWithTheViewedRevision() {
        checkOut(Cards.APPROVED, checkNotNull(world.viewedRevision) { "The shopper has not viewed the cart" })
    }

    @When("the shopper submits the same checkout again because no answer arrived")
    fun submitsTheSameCheckoutAgain() {
        world.orders.place(world.theCheckout())
    }

    @When("the shopper accepts the new prices and checks out again")
    fun acceptsTheNewPrices() {
        val refusal = world.theCheckout().lastAnswer
        checkOut(Cards.APPROVED, refusal.body.requireString("currentCartRevision"))
    }

    @Then("the order is placed with its payment approved")
    fun placedAndApproved() {
        placedWith(Status.CREATED, "approved")
    }

    @Then("the order is placed with its payment pending")
    fun placedAndPending() {
        placedWith(Status.ACCEPTED, "pending")
    }

    @Then("the shopper is told to retry later")
    fun toldToRetryLater() {
        val answer = world.theCheckout().lastAnswer
        answer.header(Headers.RETRY_AFTER).shouldNotBeNull()
    }

    @Then("the order has {int} {string} at {money}")
    fun theOrderHasALine(
        quantity: Int,
        alias: String,
        unitPrice: Long,
    ) {
        val line = orderLine(currentOrder(), alias)
        line.path("quantity").asInt() shouldBe quantity
        line.minor("unitPrice") shouldBe unitPrice
        line.minor("lineTotal") shouldBe unitPrice * quantity
    }

    @Then("the order total is {money}")
    fun theOrderTotalIs(total: Long) {
        currentOrder().minor("total") shouldBe total
    }

    @Then("the checkout is refused because the payment was declined as {string}")
    fun refusedAsDeclined(reason: String) {
        val refusal = world.theCheckout().lastAnswer
        refusal.shouldBeProblem(Status.UNPROCESSABLE, "payment-declined")
        refusal.body.string("declineReason") shouldBe reason
        world.order = world.orders.get(world.theShopper().bearer, refusal.body.requireString("orderId")).body
    }

    @Then("the order is recorded as cancelled because the payment failed")
    fun recordedAsPaymentFailed() {
        val order = currentOrder()
        order.string("orderStatus") shouldBe "cancelled"
        order.string("paymentStatus") shouldBe "failed"
        order.string("cancellationReason") shouldBe "PAYMENT_FAILED"
    }

    @Given("both shoppers have {int} {string} in their carts")
    fun bothShoppersHave(
        quantity: Int,
        alias: String,
    ) {
        listOf(world.theShopper(), world.theOtherShopper()).forEach { shopper ->
            val added = world.carts.add(CartHolder(bearer = shopper.bearer), world.product(alias).id, quantity)
            added shouldHaveStatus Status.CREATED
        }
    }

    @When("both shoppers check out at the same time paying with a card the simulator approves")
    fun bothCheckOutTogether() {
        val checkouts = listOf(world.theShopper(), world.theOtherShopper()).map { prepare(it, Cards.APPROVED) }
        Concurrency.together(checkouts.map { checkout -> { world.orders.place(checkout) } })
        raceAnswers = checkouts
    }

    @Then("exactly one order is placed")
    fun exactlyOneOrderIsPlaced() {
        val statuses = raceAnswers.map { it.lastAnswer.status }
        withClue("checkout answers ${raceAnswers.map { it.lastAnswer.describe() }}") {
            statuses.count { it == Status.CREATED } shouldBe 1
        }
    }

    @Then("the other checkout is refused because the stock is insufficient")
    fun theOtherIsRefused() {
        val loser = raceAnswers.single { it.lastAnswer.status != Status.CREATED }.lastAnswer
        loser.shouldBeProblem(Status.CONFLICT, "insufficient-stock")
    }

    @Then("both answers describe the same order")
    fun bothAnswersDescribeTheSameOrder() {
        val answers = world.theCheckout().answers
        answers shouldHaveSize 2
        answers.forEach { withClue(it.describe()) { it.status shouldBe answers.first().status } }
        answers.map { it.body.string("id") }.distinct() shouldHaveSize 1
        // The one order both answers describe is the scenario's order (its payment charges are checked next).
        world.order = answers.first().body
    }

    @Then("the shopper has exactly {int} order(s)")
    fun theShopperHasOrders(count: Int) {
        val history = world.orders.list(world.theShopper().bearer)
        history shouldHaveStatus Status.OK
        history.body.items() shouldHaveSize count
    }

    @Then("the shopper has no orders")
    fun theShopperHasNoOrders() {
        val history = world.orders.list(world.theShopper().bearer)
        history shouldHaveStatus Status.OK
        history.body.items().shouldBeEmpty()
    }

    @Then("the order has exactly {int} payment charge(s)")
    fun theOrderHasCharges(count: Int) {
        val attempts = world.orders.paymentAttempts(world.theShopper().bearer, world.orderId())
        attempts shouldHaveStatus Status.OK
        attempts.body.items() shouldHaveSize count
    }

    @Then("the checkout is refused because the stock is insufficient for {string}")
    fun refusedForInsufficientStock(alias: String) {
        val refusal = world.theCheckout().lastAnswer
        refusal.shouldBeProblem(Status.CONFLICT, "insufficient-stock")
        refusal.body.list("unavailableLines").map { it.string("productId") } shouldBe listOf(world.product(alias).id)
    }

    @Then("the checkout is refused because the price of {string} changed from {money} to {money}")
    fun refusedForAPriceChange(
        alias: String,
        oldPrice: Long,
        newPrice: Long,
    ) {
        val refusal = world.theCheckout().lastAnswer
        refusal.shouldBeProblem(Status.CONFLICT, "price-changed")
        val changed = refusal.body.list("changedLines").single { it.string("productId") == world.product(alias).id }
        changed.minor("oldPrice") shouldBe oldPrice
        changed.minor("newPrice") shouldBe newPrice
    }

    @Given("the shopper has placed a paid order for {string}")
    fun hasPlacedAPaidOrder(alias: String) {
        placePaidOrder(1, alias)
    }

    @Given("the shopper has placed a paid order for {int} {string}")
    fun hasPlacedAPaidOrderOf(
        quantity: Int,
        alias: String,
    ) {
        placePaidOrder(quantity, alias)
    }

    @When("the shopper places a paid order for {int} {string}")
    fun placesAPaidOrderOf(
        quantity: Int,
        alias: String,
    ) {
        placePaidOrder(quantity, alias)
    }

    @When("the shopper places a paid order for {string}")
    fun placesAPaidOrder(alias: String) {
        placePaidOrder(1, alias)
    }

    @Given("the shopper has placed {int} paid orders for {string}")
    fun hasPlacedSeveralPaidOrders(
        count: Int,
        alias: String,
    ) {
        repeat(count) { placePaidOrder(1, alias) }
    }

    @Given("another shopper has placed a paid order for {string}")
    fun anotherShopperHasPlacedAnOrder(alias: String) {
        val other = world.accounts.signedInShopperWithAddress()
        world.otherShopper = other
        world.carts.add(CartHolder(bearer = other.bearer), world.product(alias).id, 1) shouldHaveStatus Status.CREATED
        val checkout = prepare(other, Cards.APPROVED)
        world.orders.place(checkout) shouldHaveStatus Status.CREATED
        world.otherOrderId = checkout.lastAnswer.body.requireString("id")
    }

    @Given("the shopper has placed an order for {string} while the payment provider is unreachable")
    fun hasPlacedAPendingOrder(alias: String) {
        world.carts.add(world.shopperCart(), world.product(alias).id, 1) shouldHaveStatus Status.CREATED
        // Unreachable on every attempt: the payment service's retries cannot resolve it during the scenario.
        checkOut(Cards.UNREACHABLE_FOREVER)
        placedAndPending()
    }

    @Then("a later retry of the payment approves the order")
    fun aLaterRetryApproves() {
        val shopper = world.theShopper()
        eventually(within = Environment.paymentRetryBudget, every = Duration.ofSeconds(RETRY_POLL_SECONDS)) {
            val order = world.orders.get(shopper.bearer, world.orderId())
            order shouldHaveStatus Status.OK
            withClue(order.describe()) {
                order.body.string("orderStatus") shouldBe "placed"
                order.body.string("paymentStatus") shouldBe "approved"
            }
            world.order = order.body
        }
    }

    @When("the payment window of the order ends while its payment is still pending")
    fun thePaymentWindowEnds() {
        val shopper = world.theShopper()
        eventually(within = Environment.paymentExpiryBudget, every = Duration.ofSeconds(RETRY_POLL_SECONDS)) {
            val order = world.orders.get(shopper.bearer, world.orderId())
            order shouldHaveStatus Status.OK
            withClue(order.describe()) { order.body.string("orderStatus") shouldBe "cancelled" }
            world.order = order.body
        }
    }

    @Then("the order is recorded as cancelled because the payment expired")
    fun recordedAsPaymentExpired() {
        val order = currentOrder()
        order.string("orderStatus") shouldBe "cancelled"
        order.string("paymentStatus") shouldBe "failed"
        order.string("cancellationReason") shouldBe "PAYMENT_EXPIRED"
    }

    @Then("every payment attempt of the order is voided")
    fun everyAttemptIsVoided() {
        eventually {
            val attempts = world.orders.paymentAttempts(world.theShopper().bearer, world.orderId())
            attempts shouldHaveStatus Status.OK
            val outcomes = attempts.body.items().map { it.string("outcome") }
            withClue(attempts.describe()) {
                outcomes.shouldNotBeEmpty()
                outcomes.distinct() shouldBe listOf("voided")
            }
        }
    }

    @Then("the order has {int} payment attempts, the latest approved and the earlier ones voided")
    fun theAttemptsOfARetriedPayment(count: Int) {
        val attempts = world.orders.paymentAttempts(world.theShopper().bearer, world.orderId())
        attempts shouldHaveStatus Status.OK
        val items = attempts.body.items()
        withClue(attempts.describe()) {
            items shouldHaveSize count
            items.map { it.string("outcome") } shouldBe listOf("approved") + List(count - 1) { "voided" }
            items.map { it.path("attemptNumber").asInt() } shouldBe (count downTo 1).toList()
            items.first().string("retryOf") shouldBe items[1].string("id")
        }
    }

    private fun placePaidOrder(
        quantity: Int,
        alias: String,
    ) {
        world.carts.add(world.shopperCart(), world.product(alias).id, quantity) shouldHaveStatus Status.CREATED
        checkOut(Cards.APPROVED)
        placedAndApproved()
    }

    /** Builds a checkout from the shopper's current cart revision (or [revision]) and sends it. */
    private fun checkOut(
        card: String,
        revision: String? = null,
    ) {
        val checkout = prepare(world.theShopper(), card, revision)
        world.checkout = checkout
        world.orders.place(checkout)
        world.tracedCorrelationId = checkout.correlationId
    }

    private fun prepare(
        shopper: Shopper,
        card: String,
        revision: String? = null,
    ): Checkout {
        val cartRevision =
            revision ?: world.carts
                .view(CartHolder(bearer = shopper.bearer))
                .body
                .requireString("revision")
        val addressId = checkNotNull(shopper.addressId) { "$shopper has no saved delivery address" }
        return Checkout(shopper.bearer, Checkout.body(addressId, cartRevision, card))
    }

    private fun placedWith(
        status: Int,
        paymentStatus: String,
    ) {
        val answer = world.theCheckout().lastAnswer
        answer shouldHaveStatus status
        answer.body.string("orderStatus") shouldBe "placed"
        answer.body.string("paymentStatus") shouldBe paymentStatus
        world.order = answer.body
        world.placedOrderIds += answer.body.requireString("id")
    }

    private fun currentOrder(): JsonNode = checkNotNull(world.order) { "No order in this scenario" }

    private companion object {
        const val RETRY_POLL_SECONDS = 5L
    }

    private fun orderLine(
        order: JsonNode,
        alias: String,
    ): JsonNode =
        checkNotNull(order.list("lines").firstOrNull { it.string("productId") == world.product(alias).id }) {
            "No line for '$alias' in order ${order.string("id")}"
        }
}
