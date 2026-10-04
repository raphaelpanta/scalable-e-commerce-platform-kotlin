package com.ecommerce.catalog.infrastructure.web

import com.ecommerce.platform.observability.observedCoRouter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse

/** The HTTP surface: catalog.yaml (public, through the gateway) and catalog-internal.yaml (cart and order only). */
@Configuration(proxyBeanMethods = false)
class CatalogRoutes {
    @Bean
    fun catalogRouter(
        queries: CatalogQueryHandlers,
        admin: CatalogAdminHandlers,
        reservations: ReservationHandlers,
        pricing: PricingHandlers,
    ): RouterFunction<ServerResponse> =
        observedCoRouter {
            GET(PRODUCTS, queries::listProducts)
            POST(PRODUCTS, admin::createProduct)
            GET(PRODUCT, queries::getProduct)
            PUT(PRODUCT, admin::updateProduct)
            POST("$PRODUCT/withdrawal", admin::withdrawProduct)
            POST("$PRODUCT/stock-adjustments", admin::adjustStock)
            POST("$PRODUCT/images", admin::addProductImage)
            GET(CATEGORIES, queries::listCategories)
            POST(CATEGORIES, admin::createCategory)
            GET(CATEGORY, queries::getCategory)
            PUT(CATEGORY, admin::updateCategory)
            POST("$CATEGORY/withdrawal", admin::withdrawCategory)
            POST("/internal/reservations", reservations::reserve)
            POST("/internal/reservations/{reservationId}/commit", reservations::commit)
            POST("/internal/reservations/{reservationId}/release", reservations::release)
            GET("/internal/products/{productId}/pricing", pricing::pricing)
            POST("/internal/products/pricing", pricing::batch)
        }

    private companion object {
        const val PRODUCTS = "/api/v1/catalog/products"
        const val PRODUCT = "$PRODUCTS/{productId}"
        const val CATEGORIES = "/api/v1/catalog/categories"
        const val CATEGORY = "$CATEGORIES/{categoryId}"
    }
}
