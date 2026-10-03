package com.ecommerce.payment.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

private val RULES = SimulatedPaymentRules

/** Amounts whose minor units end in [suffix] (for example 13: 13, 113, 4913, ...). */
private fun endingIn(suffix: Long): Arb<Money> = Arb.long(0L..99_999L).map { money(it * 100 + suffix) }

private val unreachable = token(SimulatedPaymentRules.UNREACHABLE_TOKEN)
private val unreachableForever = token(SimulatedPaymentRules.UNREACHABLE_FOREVER_TOKEN)
private val declineTokens = Arb.string(0..20).map { token(SimulatedPaymentRules.DECLINE_TOKEN_PREFIX + it) }

/**
 * T061: the deterministic rules of the simulated provider (data-model section 1, payment.yaml `getSimulatorRules`),
 * evaluated in order with the first match winning and `approved` by default; refunds always succeed.
 */
class SimulatedPaymentProviderSpec :
    FunSpec({
        test("a charge that no rule matches is approved") {
            checkAll(PaymentArbs.plainToken, PaymentArbs.plainAmount) { method, amount ->
                RULES.decide(method, amount) shouldBe RuleDecision(PaymentOutcome.APPROVED, null, null)
            }
            RULES.decide(token(APPROVE_TOKEN), money(19_800)).outcome shouldBe PaymentOutcome.APPROVED
        }

        test("the unreachable token leaves the first attempt pending whatever the amount (rule 1 wins)") {
            checkAll(PaymentArbs.amount) { amount ->
                RULES.decide(unreachable, amount) shouldBe
                    RuleDecision(PaymentOutcome.PENDING, null, "provider-unreachable")
                RULES.decide(unreachable, amount, 1) shouldBe RULES.decide(unreachable, amount)
            }
            RULES.decide(unreachable, money(4_913)).outcome shouldBe PaymentOutcome.PENDING
            RULES.decide(token(SimulatedPaymentRules.UNREACHABLE_TOKEN + "x"), money(4_900)).outcome shouldBe
                PaymentOutcome.APPROVED
        }

        test("a retry of an unreachable-token charge is decided by the other rules: approved unless they decline") {
            checkAll(PaymentArbs.plainAmount, Arb.int(2..10)) { amount, attempt ->
                RULES.decide(unreachable, amount, attempt) shouldBe RuleDecision(PaymentOutcome.APPROVED, null, null)
            }
            checkAll(endingIn(13), Arb.int(2..10)) { amount, attempt ->
                RULES.decide(unreachable, amount, attempt) shouldBe
                    RuleDecision(PaymentOutcome.DECLINED, DeclineCategory.INSUFFICIENT_FUNDS, "insufficient-funds")
            }
            RULES.decide(unreachable, money(4_900), SimulatedPaymentRules.UNREACHABLE_ATTEMPTS + 1).outcome shouldBe
                PaymentOutcome.APPROVED
        }

        test("the unreachable-forever token leaves every attempt pending whatever the amount") {
            checkAll(PaymentArbs.amount, Arb.int(1..10)) { amount, attempt ->
                RULES.decide(unreachableForever, amount, attempt) shouldBe
                    RuleDecision(PaymentOutcome.PENDING, null, "provider-unreachable-forever")
            }
        }

        test("amounts ending in 13 are declined insufficient_funds, before any token rule but the first") {
            checkAll(endingIn(13), PaymentArbs.plainToken) { amount, method ->
                RULES.decide(method, amount) shouldBe
                    RuleDecision(PaymentOutcome.DECLINED, DeclineCategory.INSUFFICIENT_FUNDS, "insufficient-funds")
            }
            checkAll(endingIn(13), declineTokens) { amount, method ->
                RULES.decide(method, amount).declineCategory shouldBe DeclineCategory.INSUFFICIENT_FUNDS
            }
            RULES.decide(token(APPROVE_TOKEN), money(13)).outcome shouldBe PaymentOutcome.DECLINED
            RULES.decide(token(APPROVE_TOKEN), money(4_913)).declineCategory shouldBe DeclineCategory.INSUFFICIENT_FUNDS
            RULES.decide(token(APPROVE_TOKEN), money(131)).outcome shouldBe PaymentOutcome.APPROVED
        }

        test("amounts ending in 14 are declined card_expired, before the decline token rule") {
            checkAll(endingIn(14), PaymentArbs.plainToken) { amount, method ->
                RULES.decide(method, amount) shouldBe
                    RuleDecision(PaymentOutcome.DECLINED, DeclineCategory.CARD_EXPIRED, "card-expired")
            }
            checkAll(endingIn(14), declineTokens) { amount, method ->
                RULES.decide(method, amount).declineCategory shouldBe DeclineCategory.CARD_EXPIRED
            }
            RULES.decide(token(APPROVE_TOKEN), money(141)).outcome shouldBe PaymentOutcome.APPROVED
        }

        test("tokens starting tok_sim_decline are declined card_rejected when no amount rule matched") {
            checkAll(declineTokens, PaymentArbs.plainAmount) { method, amount ->
                RULES.decide(method, amount) shouldBe
                    RuleDecision(PaymentOutcome.DECLINED, DeclineCategory.CARD_REJECTED, "card-rejected")
            }
            RULES.decide(token("x_tok_sim_decline"), money(100)).outcome shouldBe PaymentOutcome.APPROVED
        }

        test("refunds always succeed") {
            checkAll(PaymentArbs.amount) { _ -> RULES.refundOutcome() shouldBe PaymentOutcome.APPROVED }
        }

        test("the document lists the five rules of payment.yaml in order, approved by default") {
            val document = SimulatedPaymentRules.DOCUMENT
            document.version shouldBe 2
            document.defaultOutcome shouldBe PaymentOutcome.APPROVED
            document.rules.map { it.order } shouldContainExactly listOf(1, 2, 3, 4, 5)
            document.rules.map { it.id } shouldContainExactly
                listOf(
                    "provider-unreachable",
                    "provider-unreachable-forever",
                    "insufficient-funds",
                    "card-expired",
                    "card-rejected",
                )
            document.rules.map { it.match.field.wire } shouldContainExactly
                listOf("token", "token", "amountMinor", "amountMinor", "token")
            document.rules.map { it.match.operator.wire } shouldContainExactly
                listOf("equals", "equals", "endsWith", "endsWith", "startsWith")
            document.rules.map { it.match.value } shouldContainExactly
                listOf("tok_sim_unreachable", "tok_sim_unreachable_forever", "13", "14", "tok_sim_decline")
            document.rules.map { it.outcome.wire } shouldContainExactly
                listOf("pending", "pending", "declined", "declined", "declined")
            document.rules.map { it.declineCategory?.wire } shouldContainExactly
                listOf(null, null, "insufficient_funds", "card_expired", "card_rejected")
            document.rules.map { it.maxAttemptNumber } shouldContainExactly listOf(1, null, null, null, null)
        }

        test("a rule limited to the first attempts no longer applies to later ones") {
            val match = RuleMatch(RuleField.TOKEN, RuleOperator.EQUALS, APPROVE_TOKEN)
            val limited = SimulatorRule(1, "limited", "", match, PaymentOutcome.PENDING, maxAttemptNumber = 2)
            listOf(1, 2).forEach { limited.appliesTo(token(APPROVE_TOKEN), money(1), it) shouldBe true }
            limited.appliesTo(token(APPROVE_TOKEN), money(1), 3) shouldBe false
            limited.appliesTo(token("tok_other"), money(1), 1) shouldBe false
            val unlimited = limited.copy(maxAttemptNumber = null)
            unlimited.appliesTo(token(APPROVE_TOKEN), money(1), Int.MAX_VALUE) shouldBe true
            val document = SimulatorRules(1, PaymentOutcome.APPROVED, listOf(limited))
            document.evaluate(token(APPROVE_TOKEN), money(1), 2).ruleId shouldBe "limited"
            document.evaluate(token(APPROVE_TOKEN), money(1), 3) shouldBe
                RuleDecision(PaymentOutcome.APPROVED, null, null)
            document.evaluate(token(APPROVE_TOKEN), money(1)).ruleId shouldBe "limited"
        }

        test("rules are evaluated by their order, not their position in the list, and the first match wins") {
            val prefix = RuleMatch(RuleField.TOKEN, RuleOperator.STARTS_WITH, "tok")
            val first = SimulatorRule(1, "first", "", prefix, PaymentOutcome.PENDING)
            val second =
                SimulatorRule(
                    2,
                    "second",
                    "",
                    RuleMatch(RuleField.TOKEN, RuleOperator.EQUALS, APPROVE_TOKEN),
                    PaymentOutcome.DECLINED,
                    DeclineCategory.SUSPECTED_FRAUD,
                )
            val document = SimulatorRules(1, PaymentOutcome.APPROVED, listOf(second, first))
            document.evaluate(token(APPROVE_TOKEN), money(1)).ruleId shouldBe "first"
            SimulatorRules(1, PaymentOutcome.APPROVED, listOf(second)).evaluate(token(APPROVE_TOKEN), money(1)) shouldBe
                RuleDecision(PaymentOutcome.DECLINED, DeclineCategory.SUSPECTED_FRAUD, "second")
            SimulatorRules(1, PaymentOutcome.PENDING, emptyList()).evaluate(token(APPROVE_TOKEN), money(1)) shouldBe
                RuleDecision(PaymentOutcome.PENDING, null, null)
        }

        test("operators compare the whole field: equals, prefix or suffix") {
            checkAll(Arb.string(0..10), Arb.string(0..10)) { head, tail ->
                RuleOperator.EQUALS.test(head + tail, head + tail) shouldBe true
                RuleOperator.STARTS_WITH.test(head + tail, head) shouldBe true
                RuleOperator.ENDS_WITH.test(head + tail, tail) shouldBe true
            }
            RuleOperator.EQUALS.test("abc", "ab") shouldBe false
            RuleOperator.STARTS_WITH.test("abc", "bc") shouldBe false
            RuleOperator.ENDS_WITH.test("abc", "ab") shouldBe false
            RuleField.AMOUNT_MINOR.of(token(APPROVE_TOKEN), money(4_913)) shouldBe "4913"
            RuleField.TOKEN.of(token(APPROVE_TOKEN), money(4_913)) shouldBe APPROVE_TOKEN
        }

        test("a decision becomes the provider's answer, with a reference unless the provider is unreachable") {
            val ref = reference("sim_ch_000123")
            RuleDecision(PaymentOutcome.APPROVED, null, null).toProviderDecision { ref } shouldBe
                ProviderDecision.Approved(ref)
            RuleDecision(PaymentOutcome.DECLINED, DeclineCategory.CARD_EXPIRED, "card-expired")
                .toProviderDecision { ref } shouldBe ProviderDecision.Declined(DeclineCategory.CARD_EXPIRED, ref)
            RuleDecision(PaymentOutcome.DECLINED, null, null).toProviderDecision { ref } shouldBe
                ProviderDecision.Declined(DeclineCategory.CARD_REJECTED, ref)
            RuleDecision(PaymentOutcome.PENDING, null, "provider-unreachable")
                .toProviderDecision { error("no reference while unreachable") }
                .shouldBeInstanceOf<ProviderDecision.Unreachable>()
            RuleDecision(PaymentOutcome.VOIDED, null, null)
                .toProviderDecision { error("no reference without an answer") }
                .shouldBeInstanceOf<ProviderDecision.Unreachable>()
        }

        test("a rule document refuses inconsistent rules") {
            val match = RuleMatch(RuleField.TOKEN, RuleOperator.EQUALS, "t")
            shouldThrow<IllegalArgumentException> { SimulatorRule(0, "zero", "", match, PaymentOutcome.PENDING) }
            shouldThrow<IllegalArgumentException> {
                SimulatorRule(
                    1,
                    "no-category",
                    "",
                    match,
                    PaymentOutcome.DECLINED,
                )
            }
            shouldThrow<IllegalArgumentException> {
                SimulatorRule(1, "category", "", match, PaymentOutcome.APPROVED, DeclineCategory.CARD_REJECTED)
            }
            val rule = SimulatorRule(1, "one", "", match, PaymentOutcome.PENDING)
            shouldThrow<IllegalArgumentException> { SimulatorRules(0, PaymentOutcome.APPROVED, emptyList()) }
            shouldThrow<IllegalArgumentException> { SimulatorRules(1, PaymentOutcome.DECLINED, emptyList()) }
            shouldThrow<IllegalArgumentException> { SimulatorRules(1, PaymentOutcome.VOIDED, emptyList()) }
            shouldThrow<IllegalArgumentException> { SimulatorRule(1, "voids", "", match, PaymentOutcome.VOIDED) }
            shouldThrow<IllegalArgumentException> {
                SimulatorRule(1, "zero-attempts", "", match, PaymentOutcome.PENDING, maxAttemptNumber = 0)
            }
            SimulatorRule(1, "one-attempt", "", match, PaymentOutcome.PENDING, maxAttemptNumber = 1)
                .maxAttemptNumber shouldBe 1
            SimulatorRules(1, PaymentOutcome.PENDING, emptyList()).defaultOutcome shouldBe PaymentOutcome.PENDING
            shouldThrow<IllegalArgumentException> { SimulatorRules(1, PaymentOutcome.APPROVED, listOf(rule, rule)) }
            SimulatorRule(1, "one", "", match, PaymentOutcome.PENDING).order shouldBe 1
            SimulatorRules(1, PaymentOutcome.APPROVED, listOf(rule)).rules.size shouldBe 1
        }
    })
