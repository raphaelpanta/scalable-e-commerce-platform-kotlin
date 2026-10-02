package com.ecommerce.payment.infrastructure.provider

import com.ecommerce.payment.domain.Money
import com.ecommerce.payment.domain.PaymentMethodRef
import com.ecommerce.payment.domain.PaymentProviderPort
import com.ecommerce.payment.domain.ProviderDecision
import com.ecommerce.payment.domain.ProviderReference
import com.ecommerce.payment.domain.SimulatorRules
import java.util.UUID

/**
 * The MVP adapter of [PaymentProviderPort] (FR-014, research section 11): decides every charge with the
 * deterministic [rules] document (the one `GET /api/v1/payments/simulator/rules` publishes) and issues references
 * `sim_ch_<hex>` for charges and `sim_rf_<hex>` for refunds, which it always accepts. No network, no card data.
 */
class SimulatedPaymentProvider(
    private val rules: SimulatorRules,
    private val nextSuffix: () -> String = { UUID.randomUUID().toString().replace("-", "") },
) : PaymentProviderPort {
    override suspend fun charge(
        paymentMethod: PaymentMethodRef,
        amount: Money,
    ): ProviderDecision = rules.evaluate(paymentMethod, amount).toProviderDecision { reference(CHARGE_PREFIX) }

    override suspend fun refund(
        charge: ProviderReference,
        amount: Money,
    ): ProviderReference = reference(REFUND_PREFIX)

    private fun reference(prefix: String): ProviderReference = ProviderReference(prefix + nextSuffix())

    companion object {
        /** Prefix of the references of simulated charges. */
        const val CHARGE_PREFIX: String = "sim_ch_"

        /** Prefix of the references of simulated refunds. */
        const val REFUND_PREFIX: String = "sim_rf_"
    }
}
