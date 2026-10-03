package com.ecommerce.payment.domain

/** The charge input a simulator rule looks at (`SimulatorRule.match.field` of payment.yaml). */
enum class RuleField(
    val wire: String,
) {
    /** The amount in minor units, as decimal digits. */
    AMOUNT_MINOR("amountMinor"),

    /** The payment method token. */
    TOKEN("token"),
    ;

    /** The text of this field for a charge of [amount] to [paymentMethod]. */
    fun of(
        paymentMethod: PaymentMethodRef,
        amount: Money,
    ): String =
        when (this) {
            AMOUNT_MINOR -> amount.amountMinor.toString()
            TOKEN -> paymentMethod.token
        }
}

/** How a rule compares the field with its value (`SimulatorRule.match.operator` of payment.yaml). */
enum class RuleOperator(
    val wire: String,
) {
    EQUALS("equals"),
    STARTS_WITH("startsWith"),
    ENDS_WITH("endsWith"),
    ;

    /** True when [subject] matches [value] under this operator (case-sensitive). */
    fun test(
        subject: String,
        value: String,
    ): Boolean =
        when (this) {
            EQUALS -> subject == value
            STARTS_WITH -> subject.startsWith(value)
            ENDS_WITH -> subject.endsWith(value)
        }
}

/** The condition of one rule. */
data class RuleMatch(
    val field: RuleField,
    val operator: RuleOperator,
    val value: String,
) {
    /** True when a charge of [amount] to [paymentMethod] meets this condition. */
    fun matches(
        paymentMethod: PaymentMethodRef,
        amount: Money,
    ): Boolean = operator.test(field.of(paymentMethod, amount), value)
}

/**
 * One deterministic rule of the simulated provider; [declineCategory] is present exactly for a decline. A rule with
 * [maxAttemptNumber] applies only to the attempts of an order up to that number (a retry of a pending charge is attempt
 * 2, 3, ...); without it the rule applies to every attempt. A rule never voids: voiding is not a provider answer.
 */
data class SimulatorRule(
    val order: Int,
    val id: String,
    val description: String,
    val match: RuleMatch,
    val outcome: PaymentOutcome,
    val declineCategory: DeclineCategory? = null,
    val maxAttemptNumber: Int? = null,
) {
    init {
        require(order >= 1) { "rules are ordered from 1" }
        require(outcome != PaymentOutcome.VOIDED) { "a provider never answers voided" }
        require((declineCategory != null) == (outcome == PaymentOutcome.DECLINED)) {
            "a rule names a decline category exactly when it declines"
        }
        require(maxAttemptNumber == null || maxAttemptNumber >= 1) { "attempts are numbered from 1" }
    }

    /** True when this rule decides attempt [attemptNumber] of a charge of [amount] to [paymentMethod]. */
    fun appliesTo(
        paymentMethod: PaymentMethodRef,
        amount: Money,
        attemptNumber: Int,
    ): Boolean = (maxAttemptNumber == null || attemptNumber <= maxAttemptNumber) && match.matches(paymentMethod, amount)
}

/** What the rules decide for one charge: the outcome, its decline category and the rule that matched (if any). */
data class RuleDecision(
    val outcome: PaymentOutcome,
    val declineCategory: DeclineCategory?,
    val ruleId: String?,
) {
    /** The provider's answer for this decision; [reference] is issued unless the provider is unreachable. */
    fun toProviderDecision(reference: () -> ProviderReference): ProviderDecision =
        when (outcome) {
            PaymentOutcome.APPROVED -> {
                ProviderDecision.Approved(reference())
            }

            PaymentOutcome.DECLINED -> {
                ProviderDecision.Declined(declineCategory ?: DeclineCategory.CARD_REJECTED, reference())
            }

            PaymentOutcome.PENDING, PaymentOutcome.VOIDED -> {
                ProviderDecision.Unreachable
            }
        }
}

/**
 * The rule document of the simulated provider (`SimulatorRules` of payment.yaml, FR-014): rules are evaluated in
 * [SimulatorRule.order], the first that applies wins, otherwise [defaultOutcome] applies. Changing rules is a code
 * change.
 */
data class SimulatorRules(
    val version: Int,
    val defaultOutcome: PaymentOutcome,
    val rules: List<SimulatorRule>,
) {
    init {
        require(version >= 1) { "the document version starts at 1" }
        require(defaultOutcome == PaymentOutcome.APPROVED || defaultOutcome == PaymentOutcome.PENDING) {
            "a decline needs a category and a provider never answers voided, so neither can be the default"
        }
        require(rules.map { it.order }.toSet().size == rules.size) { "rule positions are unique" }
    }

    /** The decision for attempt [attemptNumber] (1 for the first) of a charge of [amount] to [paymentMethod]. */
    fun evaluate(
        paymentMethod: PaymentMethodRef,
        amount: Money,
        attemptNumber: Int = 1,
    ): RuleDecision =
        rules
            .sortedBy { it.order }
            .firstOrNull { it.appliesTo(paymentMethod, amount, attemptNumber) }
            ?.let { RuleDecision(it.outcome, it.declineCategory, it.id) }
            ?: RuleDecision(defaultOutcome, null, null)
}

/**
 * The deterministic rules of the simulated provider (data-model section 1, research section 11): token
 * `tok_sim_unreachable` leaves the first attempt of a charge pending (provider unreachable) and lets its retry go
 * through the other rules (approved unless the amount or token declines it); token `tok_sim_unreachable_forever` leaves
 * every attempt pending, so the 30-minute payment expiry can be exercised; amounts whose minor units end in `13` are
 * declined `insufficient_funds`, ending in `14` `card_expired`; tokens starting `tok_sim_decline` are declined
 * `card_rejected`; everything else (for example `tok_sim_approve_4242`) is approved. Refunds always succeed.
 */
object SimulatedPaymentRules {
    const val UNREACHABLE_TOKEN: String = "tok_sim_unreachable"
    const val UNREACHABLE_FOREVER_TOKEN: String = "tok_sim_unreachable_forever"
    const val DECLINE_TOKEN_PREFIX: String = "tok_sim_decline"
    const val INSUFFICIENT_FUNDS_SUFFIX: String = "13"
    const val CARD_EXPIRED_SUFFIX: String = "14"

    /** The attempts `tok_sim_unreachable` leaves pending: only the first, so its first retry resolves it. */
    const val UNREACHABLE_ATTEMPTS: Int = 1

    /** The active document, published by `GET /api/v1/payments/simulator/rules`. */
    val DOCUMENT: SimulatorRules =
        SimulatorRules(
            version = 2,
            defaultOutcome = PaymentOutcome.APPROVED,
            rules =
                listOf(
                    SimulatorRule(
                        1,
                        "provider-unreachable",
                        "Token marks the provider as unreachable for the first attempt; a retry is decided by the " +
                            "other rules.",
                        RuleMatch(RuleField.TOKEN, RuleOperator.EQUALS, UNREACHABLE_TOKEN),
                        PaymentOutcome.PENDING,
                        maxAttemptNumber = UNREACHABLE_ATTEMPTS,
                    ),
                    SimulatorRule(
                        2,
                        "provider-unreachable-forever",
                        "Token marks the provider as unreachable for every attempt.",
                        RuleMatch(RuleField.TOKEN, RuleOperator.EQUALS, UNREACHABLE_FOREVER_TOKEN),
                        PaymentOutcome.PENDING,
                    ),
                    SimulatorRule(
                        3,
                        "insufficient-funds",
                        "Amounts whose last two minor digits are 13 are declined.",
                        RuleMatch(RuleField.AMOUNT_MINOR, RuleOperator.ENDS_WITH, INSUFFICIENT_FUNDS_SUFFIX),
                        PaymentOutcome.DECLINED,
                        DeclineCategory.INSUFFICIENT_FUNDS,
                    ),
                    SimulatorRule(
                        4,
                        "card-expired",
                        "Amounts whose last two minor digits are 14 are declined.",
                        RuleMatch(RuleField.AMOUNT_MINOR, RuleOperator.ENDS_WITH, CARD_EXPIRED_SUFFIX),
                        PaymentOutcome.DECLINED,
                        DeclineCategory.CARD_EXPIRED,
                    ),
                    SimulatorRule(
                        5,
                        "card-rejected",
                        "Tokens starting tok_sim_decline are declined.",
                        RuleMatch(RuleField.TOKEN, RuleOperator.STARTS_WITH, DECLINE_TOKEN_PREFIX),
                        PaymentOutcome.DECLINED,
                        DeclineCategory.CARD_REJECTED,
                    ),
                ),
        )

    /** The decision of the active rules for attempt [attemptNumber] of a charge of [amount] to [paymentMethod]. */
    fun decide(
        paymentMethod: PaymentMethodRef,
        amount: Money,
        attemptNumber: Int = 1,
    ): RuleDecision = DOCUMENT.evaluate(paymentMethod, amount, attemptNumber)

    /** The outcome of a refund: the simulator always accepts refunds. */
    fun refundOutcome(): PaymentOutcome = PaymentOutcome.APPROVED
}
