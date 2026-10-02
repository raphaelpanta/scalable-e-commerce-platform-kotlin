package com.ecommerce.catalog.infrastructure.web

import arrow.core.Either
import arrow.core.raise.either
import com.ecommerce.catalog.application.admin.AddProductImage
import com.ecommerce.catalog.application.admin.AdjustStock
import com.ecommerce.catalog.application.admin.CategoryInput
import com.ecommerce.catalog.application.admin.CreateCategory
import com.ecommerce.catalog.application.admin.CreateProduct
import com.ecommerce.catalog.application.admin.ProductInput
import com.ecommerce.catalog.application.admin.UpdateCategory
import com.ecommerce.catalog.application.admin.UpdateProduct
import com.ecommerce.catalog.application.admin.WithdrawProduct
import com.ecommerce.catalog.domain.CategoryId
import com.ecommerce.catalog.domain.ProductId
import com.ecommerce.platform.core.problem.Problem
import com.ecommerce.platform.problem.toServerResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import java.net.URI

/**
 * Inbound adapter: the operator writes of catalog.yaml. Authentication is enforced by the platform security chain
 * (401 without a token); the operator role is checked by the use cases, which answer 403 and record the refused
 * attempt for shoppers. Malformed bodies answer 400, rule violations 422.
 */
@Suppress("LongParameterList") // one use case per operator operation of catalog.yaml
class CatalogAdminHandlers(
    private val createProduct: CreateProduct,
    private val updateProduct: UpdateProduct,
    private val withdrawProduct: WithdrawProduct,
    private val adjustStock: AdjustStock,
    private val addProductImage: AddProductImage,
    private val createCategory: CreateCategory,
    private val updateCategory: UpdateCategory,
) {
    suspend fun createProduct(request: ServerRequest): ServerResponse =
        either {
            val body = request.jsonBody<ProductRequest>().bind()
            val input = body.toInput().bind()
            createProduct(callerOf(), input, body.initialStock ?: 0, body.sku).mapLeft { it.toProblem() }.bind()
        }.toServerResponse(request) { created ->
            ServerResponse
                .created(URI.create("$PRODUCTS/${created.product.id.value}"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValueAndAwait(created.toJson())
        }

    suspend fun updateProduct(request: ServerRequest): ServerResponse =
        either {
            val productId = ProductId(request.uuidPath("productId").bind())
            val input =
                request
                    .jsonBody<ProductRequest>()
                    .bind()
                    .toInput()
                    .bind()
            updateProduct(callerOf(), productId, input).mapLeft { it.toProblem() }.bind()
        }.toServerResponse(request) { ok(it.toJson()) }

    suspend fun withdrawProduct(request: ServerRequest): ServerResponse =
        either {
            val productId = ProductId(request.uuidPath("productId").bind())
            withdrawProduct(callerOf(), productId).mapLeft { it.toProblem() }.bind()
        }.toServerResponse(request) { ok(it.toJson()) }

    suspend fun adjustStock(request: ServerRequest): ServerResponse =
        either {
            val productId = ProductId(request.uuidPath("productId").bind())
            val body = request.jsonBody<StockAdjustmentRequest>().bind()
            val delta = required(body.delta, "delta").bind()
            adjustStock(callerOf(), productId, delta, body.reason).mapLeft { it.toProblem() }.bind()
        }.toServerResponse(request) { json(HttpStatus.CREATED, it.toJson()) }

    suspend fun addProductImage(request: ServerRequest): ServerResponse =
        either {
            val productId = ProductId(request.uuidPath("productId").bind())
            val body = request.jsonBody<ProductImageRequest>().bind()
            val url = required(body.url, "url").bind()
            addProductImage(callerOf(), productId, url, body.altText, body.primary ?: false)
                .mapLeft { it.toProblem() }
                .bind()
        }.toServerResponse(request) { json(HttpStatus.CREATED, it.toJson()) }

    suspend fun createCategory(request: ServerRequest): ServerResponse =
        either {
            val input =
                request
                    .jsonBody<CategoryRequest>()
                    .bind()
                    .toInput()
                    .bind()
            createCategory(callerOf(), input).mapLeft { it.toProblem() }.bind()
        }.toServerResponse(request) { category ->
            ServerResponse
                .created(URI.create("$CATEGORIES/${category.id.value}"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValueAndAwait(category.toJson())
        }

    suspend fun updateCategory(request: ServerRequest): ServerResponse =
        either {
            val categoryId = CategoryId(request.uuidPath("categoryId").bind())
            val input =
                request
                    .jsonBody<CategoryRequest>()
                    .bind()
                    .toInput()
                    .bind()
            updateCategory(callerOf(), categoryId, input).mapLeft { it.toProblem() }.bind()
        }.toServerResponse(request) { ok(it.toJson()) }

    private companion object {
        const val PRODUCTS = "/api/v1/catalog/products"
        const val CATEGORIES = "/api/v1/catalog/categories"

        fun ProductRequest.toInput(): Either<Problem, ProductInput> =
            either {
                val price = required(price, "price").bind()
                ProductInput(
                    name = required(name, "name").bind(),
                    description = description,
                    priceMinor = required(price.amountMinor, "price.amountMinor").bind(),
                    currency = required(price.currency, "price.currency").bind(),
                    categoryId = CategoryId(required(categoryId, "categoryId").bind()),
                )
            }

        fun CategoryRequest.toInput(): Either<Problem, CategoryInput> =
            required(name, "name").map { CategoryInput(it, description, parentId?.let(::CategoryId)) }
    }
}
