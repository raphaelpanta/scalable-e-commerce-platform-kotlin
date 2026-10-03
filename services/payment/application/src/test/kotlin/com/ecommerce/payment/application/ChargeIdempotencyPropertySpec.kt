package com.ecommerce.payment.application

import arrow.core.left
import arrow.core.right
import com.ecommerce.payment.domain.PaymentError
import com.ecommerce.payment.domain.PaymentEvent
import com.ecommerce.payment.domain.PaymentOutcome
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll

/**
 * FR-013 as properties of AuthoriseCharge and the `OrderPlaced` consumer, over generated requests and provider
 * decisions: a key answers one request with one attempt, a reused key with another body is refused, and an order
 * never gets a second approved charge.
 */
class ChargeIdempotencyPropertySpec :
    FunSpec({
        test("the same key with the same body replays the one stored attempt, without a new charge or event") {
            checkAll(PROPERTIES, ApplicationArbs.chargeRequest, ApplicationArbs.decision, Arb.int(1..4)) {
                request,
                decision,
                replays,
                ->
                val backend = Backend().apply { provider.decision = decision }
                val first = backend.authoriseCharge(request).getOrNull()!!

                first.created shouldBe true
                repeat(replays) {
                    backend.authoriseCharge(request) shouldBe Recorded(first.value, created = false).right()
                    backend.chargePlacedOrder(request) shouldBe PlacedOrderCharge.Converged(first.value)
                }
                backend.attempts.stored.values
                    .toList() shouldContainExactly listOf(first.value)
                backend.provider.charges shouldHaveSize 1
                backend.events.published shouldContainExactly listOf(PaymentEvent.ChargeRecorded(first.value))
            }
        }

        test("the same key with another body is a validation error that changes nothing") {
            checkAll(
                PROPERTIES,
                ApplicationArbs.chargeRequest,
                ApplicationArbs.decision,
                ApplicationArbs.chargeDrift,
            ) { request, decision, drift ->
                val backend = Backend().apply { provider.decision = decision }
                val first = backend.authoriseCharge(request).getOrNull()!!.value

                backend.authoriseCharge(drift(request)) shouldBe PaymentError.IdempotencyKeyReuse.left()
                backend.chargePlacedOrder(drift(request)) shouldBe
                    PlacedOrderCharge.Refused(PaymentError.IdempotencyKeyReuse)
                backend.attempts.stored.values
                    .toList() shouldContainExactly listOf(first)
                backend.provider.charges shouldHaveSize 1
                backend.events.published shouldHaveSize 1
            }
        }

        test("an order gets at most one approved charge, and every later key is refused without a provider call") {
            checkAll(
                PROPERTIES,
                ApplicationArbs.chargeRequest,
                Arb.list(ApplicationArbs.decision, 1..8),
            ) { template, decisions ->
                val backend = Backend()
                val results =
                    decisions.map { decision ->
                        backend.provider.decision = decision
                        backend.authoriseCharge(template.copy(idempotencyKey = newKey()))
                    }

                val stored = backend.attempts.stored.values
                stored.count { it.outcome == PaymentOutcome.APPROVED } shouldBeLessThanOrEqual 1
                val firstApproval = decisions.indexOfFirst { it.outcome == PaymentOutcome.APPROVED }
                val charged = if (firstApproval < 0) decisions.size else firstApproval + 1
                stored shouldHaveSize charged
                backend.provider.charges shouldHaveSize charged
                results.drop(charged).forEach { it shouldBe PaymentError.AlreadyCharged.left() }
                results.take(charged).forEach { it.getOrNull()!!.created shouldBe true }
            }
        }

        test("an approved charge of another order never blocks this one") {
            checkAll(PROPERTIES, ApplicationArbs.chargeRequest, ApplicationArbs.chargeRequest) { first, second ->
                val backend = Backend()
                backend.authoriseCharge(first)
                backend.authoriseCharge(second).getOrNull()!!.created shouldBe true
                backend.attempts.stored.values
                    .count { it.outcome == PaymentOutcome.APPROVED } shouldBe 2
                backend.refunds.stored.values
                    .shouldBeEmpty()
            }
        }
    })
