package com.ecommerce.acceptance.steps

import com.ecommerce.acceptance.support.Budgets
import com.ecommerce.acceptance.support.ScenarioWorld
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
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import tools.jackson.databind.JsonNode
import java.time.Instant

/** Order history, cancellation and the operator lifecycle (user story 5, order.yaml and payment.yaml). */
class OrderTrackingSteps(
    private val world: ScenarioWorld,
) {
    @When("the shopper views their order history")
    fun viewsTheHistory() {
        world.orders.list(world.theShopper().bearer) shouldHaveStatus Status.OK
    }

    @Then("the history lists the shopper's {int} orders, newest first")
    fun historyNewestFirst(count: Int) {
        val history = world.api.last.body
        val listed = history.items()
        listed.map { it.string("id") } shouldContainExactly world.placedOrderIds.takeLast(count).reversed()
        val dates = listed.map { Instant.parse(it.requireString("createdAt")) }
        withClue("orders ordered by creation, newest first") { dates shouldBe dates.sortedDescending() }
    }

    @Then("each order shows its order status, payment status, total and date")
    fun eachOrderIsComplete() {
        world.api.last.body.items().forEach { order ->
            withClue("order ${order.string("id")}") {
                order.requireString("orderStatus").shouldNotBeBlank()
                order.requireString("paymentStatus").shouldNotBeBlank()
                order.path("total").has("amountMinor") shouldBe true
                order.requireString("createdAt").shouldNotBeBlank()
            }
        }
    }

    @Then("the other shopper's order is neither listed nor viewable by the shopper")
    fun othersOrderIsInvisible() {
        val otherOrder = checkNotNull(world.otherOrderId) { "No other shopper's order" }
        val history = world.api.last.body
        val listed = history.items().map { it.string("id") }
        listed shouldNotContain otherOrder
        world.orders.get(world.theShopper().bearer, otherOrder) shouldHaveStatus Status.NOT_FOUND
    }

    @Given("an operator has moved the order to {word}")
    fun operatorHasMovedTheOrder(orderStatus: String) {
        world.orders.transition(world.operator(), world.orderId(), orderStatus) shouldHaveStatus Status.OK
    }

    @Given("an operator has marked the order as {word}")
    fun operatorHasMarkedTheOrder(orderStatus: String) {
        operatorHasMovedTheOrder(orderStatus)
    }

    @When("an operator marks the order as {word}")
    fun operatorMarksTheOrder(orderStatus: String) {
        operatorHasMovedTheOrder(orderStatus)
    }

    @When("an operator ships the order")
    fun operatorShipsTheOrder() {
        operatorHasMovedTheOrder("preparing")
        operatorHasMovedTheOrder("shipped")
    }

    @When("an operator tries to mark the order as {word}")
    fun operatorTriesToMark(orderStatus: String) {
        world.orders.transition(world.operator(), world.orderId(), orderStatus)
    }

    @When("an operator tries to move the order to {word}")
    fun operatorTriesToMove(orderStatus: String) {
        operatorTriesToMark(orderStatus)
    }

    @Then("the shopper sees the order as shipped with the time of the change")
    fun seesTheOrderShipped() {
        val order = ownOrder()
        order.string("orderStatus") shouldBe "shipped"
        val change =
            order.list("statusHistory").lastOrNull { it.string("kind") == "order" && it.string("status") == "shipped" }
        withClue("status history of the order: $order") { change.shouldNotBeNull() }
        val shippedAt = Instant.parse(checkNotNull(change).requireString("at"))
        shippedAt.isBefore(Instant.parse(order.requireString("createdAt"))) shouldBe false
        change.string("by") shouldBe world.accounts.operatorAccountId()
    }

    @Then("a shipping notification is produced for the order")
    fun aShippingNotificationIsProduced() {
        eventually(within = Budgets.notification) {
            NotificationSteps.channelsOf(world, "order_shipped") shouldContain "email"
        }
    }

    @When("the shopper cancels the order")
    fun cancelsTheOrder() {
        world.orders.cancel(world.theShopper().bearer, world.orderId()) shouldHaveStatus Status.OK
    }

    @When("the shopper tries to cancel the order")
    fun triesToCancel() {
        world.orders.cancel(world.theShopper().bearer, world.orderId())
    }

    @Then("the order is cancelled at the shopper's request")
    fun cancelledAtTheShoppersRequest() {
        val order = world.api.last.body
        order.string("orderStatus") shouldBe "cancelled"
        order.string("cancellationReason") shouldBe "SHOPPER_REQUEST"
    }

    @Then("a refund of the order total is recorded with the payment provider")
    fun aRefundIsRecorded() {
        val total = checkNotNull(world.order).minor("total")
        eventually {
            val refunds = world.orders.refunds(world.theShopper().bearer, world.orderId())
            refunds shouldHaveStatus Status.OK
            refunds.body.items().map { it.minor("amount") } shouldContainExactly listOf(total)
        }
    }

    @Then("the cancellation is refused with the reason")
    fun cancellationRefused() {
        val refusal = world.api.last
        refusal.shouldBeProblem(Status.CONFLICT, "order-not-cancellable")
        refusal.body.requireString("detail").shouldNotBeBlank()
    }

    @Then("the status change is refused as not allowed by the lifecycle")
    fun transitionRefused() {
        world.api.last.shouldBeProblem(Status.CONFLICT, "invalid-transition")
    }

    @Then("the order is still {word}")
    fun theOrderIsStill(orderStatus: String) {
        ownOrder().string("orderStatus") shouldBe orderStatus
    }

    @Then("the order is still placed with its payment pending")
    fun stillPlacedAndPending() {
        val order = ownOrder()
        order.string("orderStatus") shouldBe "placed"
        order.string("paymentStatus") shouldBe "pending"
    }

    private fun ownOrder(): JsonNode {
        val response = world.orders.get(world.theShopper().bearer, world.orderId())
        response shouldHaveStatus Status.OK
        return response.body
    }
}
