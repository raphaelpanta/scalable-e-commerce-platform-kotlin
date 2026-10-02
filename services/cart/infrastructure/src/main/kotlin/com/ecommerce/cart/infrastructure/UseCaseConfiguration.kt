package com.ecommerce.cart.infrastructure

import com.ecommerce.cart.application.AddLine
import com.ecommerce.cart.application.AnonymousTokens
import com.ecommerce.cart.application.CartEvents
import com.ecommerce.cart.application.CartRepository
import com.ecommerce.cart.application.CartStore
import com.ecommerce.cart.application.CatalogPricing
import com.ecommerce.cart.application.ClearCart
import com.ecommerce.cart.application.GetCart
import com.ecommerce.cart.application.MergeCarts
import com.ecommerce.cart.application.RemoveLine
import com.ecommerce.cart.application.Transactions
import com.ecommerce.cart.application.UpdateLineQuantity
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** Wires the shopper-facing use cases of the application module onto the adapters of [AdapterConfiguration]. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CartProperties::class)
class UseCaseConfiguration {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun cartStore(
        repository: CartRepository,
        pricing: CatalogPricing,
        properties: CartProperties,
        clock: Clock,
    ): CartStore = CartStore(repository, pricing, properties.currency, clock)

    @Bean
    fun getCart(store: CartStore): GetCart = GetCart(store)

    @Bean
    fun addLine(
        store: CartStore,
        tokens: AnonymousTokens,
    ): AddLine = AddLine(store, tokens)

    @Bean
    fun updateLineQuantity(store: CartStore): UpdateLineQuantity = UpdateLineQuantity(store)

    @Bean
    fun removeLine(store: CartStore): RemoveLine = RemoveLine(store)

    @Bean
    fun clearCart(store: CartStore): ClearCart = ClearCart(store)

    @Bean
    fun mergeCarts(
        store: CartStore,
        transactions: Transactions,
        events: CartEvents,
    ): MergeCarts = MergeCarts(store, transactions, events)
}
