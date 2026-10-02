package com.ecommerce.payment.application

import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import com.ecommerce.payment.domain.Caller
import com.ecommerce.payment.domain.PageRequest
import com.ecommerce.payment.domain.PaymentAttemptId
import com.ecommerce.payment.domain.PaymentError
import com.ecommerce.payment.domain.ProviderDecision
import com.ecommerce.payment.domain.RefundId
import com.ecommerce.payment.domain.RefundRecord
import com.ecommerce.payment.domain.RefundRequest
import com.ecommerce.payment.domain.Role
import com.ecommerce.payment.domain.SimulatedPaymentRules
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.util.UUID

private val SHOPPER = Caller(ADA, setOf(Role.SHOPPER))
private val STRANGER = Caller(GRACE, setOf(Role.SHOPPER))
private val OPERATOR = Caller(OPERATOR_ID, setOf(Role.OPERATOR))
private val PAGE = PageRequest.of(0, PageRequest.DEFAULT_SIZE).getOrElse { error(it) }

/** The public reads of payment.yaml: owner or operator, 404 for other shoppers, 403 for the rules. */
class PaymentQueriesSpec :
    FunSpec({
        test("an attempt is visible to its owner and operators only") {
            val backend = Backend()
            val charge = backend.approvedCharge()
            val get = GetPaymentAttempt(backend.attempts)

            get(SHOPPER, charge.id) shouldBe charge.right()
            get(OPERATOR, charge.id) shouldBe charge.right()
            get(STRANGER, charge.id) shouldBe PaymentError.NotFound.left()
            get(SHOPPER, PaymentAttemptId(UUID.randomUUID())) shouldBe PaymentError.NotFound.left()
        }

        test("the attempts of an order are listed newest first for its owner and operators") {
            val backend = Backend()
            val orderId = newOrder()
            val first = backend.stored(attemptFor(chargeRequest(orderId), ProviderDecision.Unreachable, NOW))
            val second = backend.stored(attemptFor(chargeRequest(orderId), at = NOW.plusSeconds(1)))
            backend.approvedCharge()
            val list = ListPaymentAttemptsForOrder(backend.attempts)

            val page = list(SHOPPER, orderId, PAGE).getOrNull()!!
            page.items shouldContainExactly listOf(second, first)
            page.totalItems shouldBe 2
            list(OPERATOR, orderId, PAGE).getOrNull()!!.items shouldContainExactly listOf(second, first)
            list(STRANGER, orderId, PAGE) shouldBe PaymentError.NotFound.left()
            list(STRANGER, newOrder(), PAGE).getOrNull()!!.items.shouldBeEmpty()
        }

        test("refunds are listed and read by the owner and operators only") {
            val backend = Backend()
            val charge = backend.approvedCharge()
            val refund =
                backend.stored(
                    RefundRecord.of(
                        RefundId(UUID.randomUUID()),
                        charge,
                        RefundRequest(charge.orderId, charge.id, charge.amount, newKey()),
                        REFUND_REF,
                        NOW,
                    ),
                )
            val list = ListRefundsForOrder(backend.attempts, backend.refunds)
            val get = GetRefund(backend.refunds)

            list(SHOPPER, charge.orderId, PAGE).getOrNull()!!.items shouldContainExactly listOf(refund)
            list(OPERATOR, charge.orderId, PAGE).getOrNull()!!.totalItems shouldBe 1
            list(STRANGER, charge.orderId, PAGE) shouldBe PaymentError.NotFound.left()
            list(STRANGER, newOrder(), PAGE).getOrNull()!!.items.shouldBeEmpty()
            get(SHOPPER, refund.id) shouldBe refund.right()
            get(OPERATOR, refund.id) shouldBe refund.right()
            get(STRANGER, refund.id) shouldBe PaymentError.NotFound.left()
            get(OPERATOR, RefundId(UUID.randomUUID())) shouldBe PaymentError.NotFound.left()
        }

        test("the simulator rules are for operators only") {
            val rules = GetSimulatorRules(SimulatedPaymentRules.DOCUMENT)
            rules(OPERATOR) shouldBe SimulatedPaymentRules.DOCUMENT.right()
            rules(SHOPPER) shouldBe PaymentError.Forbidden.left()
        }
    })
