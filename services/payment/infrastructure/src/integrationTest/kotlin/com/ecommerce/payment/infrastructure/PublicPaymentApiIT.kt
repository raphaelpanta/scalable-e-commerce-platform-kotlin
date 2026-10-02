package com.ecommerce.payment.infrastructure

import com.ecommerce.platform.core.problem.ProblemType
import com.ecommerce.platform.testing.ProblemAssertions.expectProblem
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

private const val DECLINED_AMOUNT = 4_913L
private const val BAD_REQUEST = 400
private const val DEFAULT_PAGE_SIZE = 20

/** The public reads of payment.yaml: owner shopper or operator, 404 for other shoppers, rules for operators. */
class PublicPaymentApiIT : PaymentIntegrationTest() {
    private val operatorId: UUID = UUID.randomUUID()

    @Test
    fun `an attempt is readable by its owner and operators, other shoppers get 404`() {
        val charge = Charge()
        val attemptId = charged(charge)["attemptId"].toString()

        val attempt = body(read("$PAYMENTS/attempts/$attemptId", charge.owner).expectStatus().isOk)

        attempt["id"] shouldBe attemptId
        attempt["orderId"] shouldBe charge.orderId.toString()
        attempt["amount"] shouldBe mapOf("amountMinor" to charge.amountMinor.toInt(), "currency" to "BRL")
        attempt["outcome"] shouldBe "approved"
        attempt["idempotencyKey"] shouldBe charge.key.toString()
        attempt.containsKey("declineReason") shouldBe false
        read("$PAYMENTS/attempts/$attemptId", operatorId, OPERATOR).expectStatus().isOk
        read("$PAYMENTS/attempts/$attemptId", UUID.randomUUID()).expectProblem(ProblemType.NOT_FOUND)
        read("$PAYMENTS/attempts/${UUID.randomUUID()}", charge.owner).expectProblem(ProblemType.NOT_FOUND)
        read("$PAYMENTS/attempts/not-a-uuid", charge.owner).expectProblem(ProblemType.VALIDATION, BAD_REQUEST)
        client
            .get()
            .uri("$PAYMENTS/attempts/$attemptId")
            .exchange()
            .expectProblem(ProblemType.UNAUTHORIZED)
    }

    @Test
    fun `the attempts of an order are listed newest first for its owner`() {
        val declined = Charge(amountMinor = DECLINED_AMOUNT)
        val first = charged(declined)["attemptId"]
        val second = charged(declined.copy(key = UUID.randomUUID(), amountMinor = DECLINED_AMOUNT + 1))["attemptId"]

        val page = body(read("$PAYMENTS/attempts?orderId=${declined.orderId}", declined.owner).expectStatus().isOk)

        (page["items"] as List<*>).map { (it as Map<*, *>)["id"] } shouldContainExactly listOf(second, first)
        ((page["items"] as List<*>).last() as Map<*, *>)["declineReason"] shouldBe "insufficient_funds"
        page["totalItems"] shouldBe 2
        page["page"] shouldBe 0
        page["size"] shouldBe DEFAULT_PAGE_SIZE
        read("$PAYMENTS/attempts?orderId=${declined.orderId}", UUID.randomUUID()).expectProblem(ProblemType.NOT_FOUND)
        read("$PAYMENTS/attempts?orderId=${declined.orderId}&size=1", operatorId, OPERATOR)
            .expectStatus()
            .isOk
        val empty = body(read("$PAYMENTS/attempts?orderId=${UUID.randomUUID()}", declined.owner).expectStatus().isOk)
        (empty["items"] as List<*>).shouldBeEmpty()
        read("$PAYMENTS/attempts", declined.owner).expectProblem(ProblemType.VALIDATION, BAD_REQUEST)
        read("$PAYMENTS/attempts?orderId=${declined.orderId}&size=101", declined.owner)
            .expectProblem(ProblemType.VALIDATION, BAD_REQUEST)
    }

    @Test
    fun `refunds are listed and read by the owner and operators only`() {
        val charge = Charge()
        val attemptId = charged(charge)["attemptId"].toString()
        val key = UUID.randomUUID()
        val refundId = body(postRefund(charge, attemptId, key).expectStatus().isCreated)["refundId"].toString()

        val page = body(read("$PAYMENTS/refunds?orderId=${charge.orderId}", charge.owner).expectStatus().isOk)
        val refund = (page["items"] as List<*>).single() as Map<*, *>
        refund["id"] shouldBe refundId
        refund["refundOf"] shouldBe attemptId
        refund["outcome"] shouldBe "approved"
        refund["idempotencyKey"] shouldBe key.toString()
        refund["amount"] shouldBe mapOf("amountMinor" to charge.amountMinor.toInt(), "currency" to "BRL")
        body(read("$PAYMENTS/refunds/$refundId", charge.owner).expectStatus().isOk)["refundOf"] shouldBe attemptId
        read("$PAYMENTS/refunds/$refundId", operatorId, OPERATOR).expectStatus().isOk
        read("$PAYMENTS/refunds/$refundId", UUID.randomUUID()).expectProblem(ProblemType.NOT_FOUND)
        read("$PAYMENTS/refunds?orderId=${charge.orderId}", UUID.randomUUID()).expectProblem(ProblemType.NOT_FOUND)
    }

    @Test
    fun `the simulator rules are published to operators, shoppers get 403`() {
        val rules = body(read("$PAYMENTS/simulator/rules", operatorId, OPERATOR).expectStatus().isOk)

        rules["version"] shouldBe 1
        rules["defaultOutcome"] shouldBe "approved"
        val list = (rules["rules"] as List<*>).map { it as Map<*, *> }
        list.map { it["id"] } shouldContainExactly
            listOf("provider-unreachable", "insufficient-funds", "card-expired", "card-rejected")
        list.first()["match"] shouldBe
            mapOf("field" to "token", "operator" to "equals", "value" to "tok_sim_unreachable")
        list.first().containsKey("declineReason") shouldBe false
        list.map { it["declineReason"] } shouldContainExactly
            listOf(null, "insufficient_funds", "card_expired", "card_rejected")
        read("$PAYMENTS/simulator/rules", UUID.randomUUID()).expectProblem(ProblemType.FORBIDDEN)
    }
}
