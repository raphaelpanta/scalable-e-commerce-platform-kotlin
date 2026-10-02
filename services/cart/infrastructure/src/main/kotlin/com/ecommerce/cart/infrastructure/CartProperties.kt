package com.ecommerce.cart.infrastructure

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** `cart.*` (application.yml): the catalog's base URL, the platform currency and the anonymous-cart purge. */
@ConfigurationProperties("cart")
data class CartProperties(
    /** Base URL of the catalog's internal API (`CATALOG_URL`). */
    val catalogUrl: String = "http://localhost:8080",
    /** Currency of totals (`PLATFORM_CURRENCY`). */
    val currency: String = "BRL",
    /** Anonymous carts unchanged for this long are deleted. */
    val anonymousIdleTimeout: Duration = Duration.ofDays(DEFAULT_IDLE_DAYS),
    /** How often idle anonymous carts are looked for. */
    val purgeInterval: Duration = Duration.ofHours(1),
) {
    private companion object {
        const val DEFAULT_IDLE_DAYS = 30L
    }
}
