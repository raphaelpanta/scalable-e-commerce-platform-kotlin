package com.ecommerce.catalog.infrastructure.web

import arrow.core.raise.either
import com.ecommerce.catalog.application.GetCategory
import com.ecommerce.catalog.application.GetProduct
import com.ecommerce.catalog.application.ListCategories
import com.ecommerce.catalog.application.ListProducts
import com.ecommerce.catalog.application.SearchProducts
import com.ecommerce.catalog.domain.CategoryId
import com.ecommerce.catalog.domain.ProductId
import com.ecommerce.platform.problem.toServerResponse
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse

/**
 * Inbound adapter: the anonymous reads of catalog.yaml (`listProducts`, `getProduct`, `listCategories`,
 * `getCategory`). A bearer token is optional; with the operator role the quantity and, on request, withdrawn
 * products are included. Malformed query parameters answer 400; unknown or hidden records 404.
 */
class CatalogQueryHandlers(
    private val listProducts: ListProducts,
    private val searchProducts: SearchProducts,
    private val getProduct: GetProduct,
    private val listCategories: ListCategories,
    private val getCategory: GetCategory,
) {
    suspend fun listProducts(request: ServerRequest): ServerResponse =
        either {
            val page = request.pageRequest().bind()
            val categoryId = request.uuidQuery("categoryId").bind()?.let(::CategoryId)
            val includeWithdrawn = request.booleanQuery("includeWithdrawn").bind()
            val query = request.queryParam("q").orElse(null)?.takeIf { it.isNotBlank() }
            val caller = callerOf()
            if (query == null) {
                listProducts(caller, categoryId, includeWithdrawn, page)
            } else {
                searchProducts(caller, query, categoryId, includeWithdrawn, page)
                    .mapLeft { it.toProblem(BAD_REQUEST) }
                    .bind()
            }
        }.toServerResponse(request) { products -> ok(products.toJson { it.toJson() }) }

    suspend fun getProduct(request: ServerRequest): ServerResponse =
        either {
            val productId = ProductId(request.uuidPath("productId").bind())
            getProduct(callerOf(), productId).mapLeft { it.toProblem() }.bind()
        }.toServerResponse(request) { ok(it.toJson()) }

    suspend fun listCategories(request: ServerRequest): ServerResponse =
        either {
            val page = request.pageRequest().bind()
            val parentId = request.uuidQuery("parentId").bind()?.let(::CategoryId)
            listCategories(parentId, page)
        }.toServerResponse(request) { categories -> ok(categories.toJson { it.toJson() }) }

    suspend fun getCategory(request: ServerRequest): ServerResponse =
        either {
            val categoryId = CategoryId(request.uuidPath("categoryId").bind())
            getCategory(categoryId).mapLeft { it.toProblem() }.bind()
        }.toServerResponse(request) { ok(it.toJson()) }
}
