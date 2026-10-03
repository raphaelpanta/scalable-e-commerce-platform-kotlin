package com.ecommerce.catalog.infrastructure.web

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import com.ecommerce.catalog.application.Caller
import com.ecommerce.catalog.application.OperatorAction
import com.ecommerce.catalog.application.admin.AddProductImage
import com.ecommerce.catalog.application.admin.AdjustStock
import com.ecommerce.catalog.application.admin.AuthorizeOperator
import com.ecommerce.catalog.application.admin.CategoryInput
import com.ecommerce.catalog.application.admin.CreateCategory
import com.ecommerce.catalog.application.admin.CreateProduct
import com.ecommerce.catalog.application.admin.ProductInput
import com.ecommerce.catalog.application.admin.UpdateCategory
import com.ecommerce.catalog.application.admin.UpdateProduct
import com.ecommerce.catalog.application.admin.WithdrawCategory
import com.ecommerce.catalog.application.admin.WithdrawProduct
import com.ecommerce.catalog.domain.CategoryId
import com.ecommerce.catalog.domain.ProductId
import com.ecommerce.platform.core.problem.Problem
import com.ecommerce.platform.core.values.Uuids
import com.ecommerce.platform.problem.toServerResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import java.net.URI

/** The use cases behind the operator writes of catalog.yaml. */
@Suppress("LongParameterList") // one use case per operator operation of catalog.yaml
class CatalogAdminUseCases(
    val authorizeOperator: AuthorizeOperator,
    val createProduct: CreateProduct,
    val updateProduct: UpdateProduct,
    val withdrawProduct: WithdrawProduct,
    val adjustStock: AdjustStock,
    val addProductImage: AddProductImage,
    val createCategory: CreateCategory,
    val updateCategory: UpdateCategory,
    val withdrawCategory: WithdrawCategory,
)

/**
 * Inbound adapter: the operator writes of catalog.yaml. Authentication is enforced by the platform security chain
 * (401 without a token). The operator role is checked first, before the path or the body is read, so a caller
 * without it always gets 403 and an audit entry whatever the request holds (US7 scenario 4, Constitution III); the
 * use cases check it again. Malformed requests then answer 400, rule violations 422.
 */
class CatalogAdminHandlers(
    private val useCases: CatalogAdminUseCases,
) {
    suspend fun createProduct(request: ServerRequest): ServerResponse =
        either {
            val caller = operator(request, OperatorAction.CREATE_PRODUCT, null)
            val body = request.jsonBody<ProductRequest>().bind()
            val input = body.toInput().bind()
            useCases
                .createProduct(caller, input, body.initialStock ?: 0, body.sku)
                .mapLeft { it.toProblem() }
                .bind()
        }.toServerResponse(request) { created ->
            ServerResponse
                .created(URI.create("$PRODUCTS/${created.product.id.value}"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValueAndAwait(created.toJson())
        }

    suspend fun updateProduct(request: ServerRequest): ServerResponse =
        either {
            val caller = operator(request, OperatorAction.UPDATE_PRODUCT, PRODUCT_ID)
            val productId = ProductId(request.uuidPath(PRODUCT_ID).bind())
            val input =
                request
                    .jsonBody<ProductRequest>()
                    .bind()
                    .toInput()
                    .bind()
            useCases.updateProduct(caller, productId, input).mapLeft { it.toProblem() }.bind()
        }.toServerResponse(request) { ok(it.toJson()) }

    suspend fun withdrawProduct(request: ServerRequest): ServerResponse =
        either {
            val caller = operator(request, OperatorAction.WITHDRAW_PRODUCT, PRODUCT_ID)
            val productId = ProductId(request.uuidPath(PRODUCT_ID).bind())
            useCases.withdrawProduct(caller, productId).mapLeft { it.toProblem() }.bind()
        }.toServerResponse(request) { ok(it.toJson()) }

    suspend fun adjustStock(request: ServerRequest): ServerResponse =
        either {
            val caller = operator(request, OperatorAction.ADJUST_STOCK, PRODUCT_ID)
            val productId = ProductId(request.uuidPath(PRODUCT_ID).bind())
            val body = request.jsonBody<StockAdjustmentRequest>().bind()
            val delta = required(body.delta, "delta").bind()
            useCases.adjustStock(caller, productId, delta, body.reason).mapLeft { it.toProblem() }.bind()
        }.toServerResponse(request) { json(HttpStatus.CREATED, it.toJson()) }

    suspend fun addProductImage(request: ServerRequest): ServerResponse =
        either {
            val caller = operator(request, OperatorAction.ADD_PRODUCT_IMAGE, PRODUCT_ID)
            val productId = ProductId(request.uuidPath(PRODUCT_ID).bind())
            val body = request.jsonBody<ProductImageRequest>().bind()
            val url = required(body.url, "url").bind()
            useCases
                .addProductImage(caller, productId, url, body.altText, body.primary ?: false)
                .mapLeft { it.toProblem() }
                .bind()
        }.toServerResponse(request) { json(HttpStatus.CREATED, it.toJson()) }

    suspend fun createCategory(request: ServerRequest): ServerResponse =
        either {
            val caller = operator(request, OperatorAction.CREATE_CATEGORY, null)
            val input =
                request
                    .jsonBody<CategoryRequest>()
                    .bind()
                    .toInput()
                    .bind()
            useCases.createCategory(caller, input).mapLeft { it.toProblem() }.bind()
        }.toServerResponse(request) { category ->
            ServerResponse
                .created(URI.create("$CATEGORIES/${category.id.value}"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValueAndAwait(category.toJson())
        }

    suspend fun updateCategory(request: ServerRequest): ServerResponse =
        either {
            val caller = operator(request, OperatorAction.UPDATE_CATEGORY, CATEGORY_ID)
            val categoryId = CategoryId(request.uuidPath(CATEGORY_ID).bind())
            val input =
                request
                    .jsonBody<CategoryRequest>()
                    .bind()
                    .toInput()
                    .bind()
            useCases.updateCategory(caller, categoryId, input).mapLeft { it.toProblem() }.bind()
        }.toServerResponse(request) { ok(it.toJson()) }

    suspend fun withdrawCategory(request: ServerRequest): ServerResponse =
        either {
            val caller = operator(request, OperatorAction.WITHDRAW_CATEGORY, CATEGORY_ID)
            val categoryId = CategoryId(request.uuidPath(CATEGORY_ID).bind())
            useCases.withdrawCategory(caller, categoryId).mapLeft { it.toProblem() }.bind()
        }.toServerResponse(request) { ok(it.toJson()) }

    /**
     * The caller when it holds the operator role; otherwise the 403 problem, after the refused attempt at [action] was
     * audited with the target id of the path variable [targetVariable] when it is a UUID (null otherwise).
     */
    private suspend fun Raise<Problem>.operator(
        request: ServerRequest,
        action: OperatorAction,
        targetVariable: String?,
    ): Caller {
        val caller = callerOf()
        val target = targetVariable?.let { Uuids.parse(request.pathVariable(it), it).getOrNull() }
        useCases.authorizeOperator(caller, action, target).mapLeft { it.toProblem() }.bind()
        return caller
    }

    private companion object {
        const val PRODUCTS = "/api/v1/catalog/products"
        const val CATEGORIES = "/api/v1/catalog/categories"
        const val PRODUCT_ID = "productId"
        const val CATEGORY_ID = "categoryId"

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
