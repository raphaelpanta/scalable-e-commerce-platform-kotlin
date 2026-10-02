package com.ecommerce.cart.infrastructure

import com.ecommerce.cart.application.CartStore
import com.ecommerce.cart.application.ClearAccountCart
import com.ecommerce.cart.application.DeleteAccountCart
import com.ecommerce.cart.application.GetAccountCart
import com.ecommerce.cart.application.PurgeIdleCarts
import com.ecommerce.cart.application.RemoveOrderedLines
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Wires the use cases behind the internal API, the event consumers and the purge job. */
@Configuration(proxyBeanMethods = false)
class AccountCartConfiguration {
    @Bean
    fun getAccountCart(store: CartStore): GetAccountCart = GetAccountCart(store)

    @Bean
    fun clearAccountCart(store: CartStore): ClearAccountCart = ClearAccountCart(store)

    @Bean
    fun removeOrderedLines(store: CartStore): RemoveOrderedLines = RemoveOrderedLines(store)

    @Bean
    fun deleteAccountCart(store: CartStore): DeleteAccountCart = DeleteAccountCart(store)

    @Bean
    fun purgeIdleCarts(
        store: CartStore,
        properties: CartProperties,
    ): PurgeIdleCarts = PurgeIdleCarts(store, properties.anonymousIdleTimeout)
}
