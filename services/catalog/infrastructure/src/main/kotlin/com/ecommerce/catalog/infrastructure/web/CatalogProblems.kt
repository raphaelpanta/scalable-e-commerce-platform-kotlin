package com.ecommerce.catalog.infrastructure.web

import arrow.core.nonEmptyListOf
import com.ecommerce.catalog.domain.CatalogError
import com.ecommerce.platform.core.problem.Problem
import com.ecommerce.platform.core.problem.ProblemType
import com.ecommerce.platform.core.result.ValidationError
import java.util.Locale

/** HTTP status of validation problems on the public API (catalog.yaml `UnprocessableEntity`). */
const val UNPROCESSABLE: Int = 422

/** HTTP status of validation problems on the internal API (catalog-internal.yaml `BadRequest`). */
const val BAD_REQUEST: Int = 400

/** Detail of the `insufficient-stock` problem (pact-interactions.md 2.3). */
const val INSUFFICIENT_STOCK_DETAIL: String = "One or more products cannot be reserved in the requested quantity."

/**
 * The RFC 9457 problem of a refused catalogue operation: validation problems with [validationStatus] (422 on the
 * public API, 400 on the internal one), 404 for unknown or hidden records, 409 for state conflicts, 403 without
 * the operator role and the `insufficient-stock` problem with every short line in `unavailableLines`.
 */
fun CatalogError.toProblem(validationStatus: Int = UNPROCESSABLE): Problem =
    when (this) {
        is CatalogError.Invalid -> {
            Problem.validation(issues.map { ValidationError(it.field, it.reason) }, status = validationStatus)
        }

        is CatalogError.InvalidHierarchy -> {
            Problem.validation(nonEmptyListOf(ValidationError("parentId", reason)), status = validationStatus)
        }

        is CatalogError.TooManyImages -> {
            Problem.validation(nonEmptyListOf(ValidationError("url", "a product holds at most 10 images")))
        }

        is CatalogError.StockBelowReserved -> {
            Problem.validation(
                nonEmptyListOf(ValidationError("delta", "would make available quantity negative")),
                "Stock cannot become negative: $available units are available.",
            )
        }

        is CatalogError.InsufficientStock -> {
            Problem.of(
                ProblemType.INSUFFICIENT_STOCK,
                INSUFFICIENT_STOCK_DETAIL,
                extensions = mapOf("unavailableLines" to lines.map { it.toJson() }),
            )
        }

        is CatalogError.Forbidden -> {
            Problem.forbidden("Operator role required.")
        }

        else -> {
            notFoundOrConflict()
        }
    }

/** The 404 and 409 problems. */
private fun CatalogError.notFoundOrConflict(): Problem =
    when (this) {
        is CatalogError.ProductNotFound -> {
            Problem.notFound("Product not found.")
        }

        is CatalogError.CategoryNotFound -> {
            Problem.notFound("Category not found.")
        }

        is CatalogError.ReservationNotFound -> {
            Problem.notFound("Reservation not found.")
        }

        is CatalogError.AlreadyWithdrawn -> {
            Problem.conflict("The product is already withdrawn.")
        }

        is CatalogError.DuplicateSku -> {
            Problem.conflict("Another product has the SKU ${sku.value}.")
        }

        is CatalogError.DuplicateCategoryName -> {
            Problem.conflict("Another category of the parent is named ${name.value}.")
        }

        is CatalogError.IllegalTransition -> {
            Problem.conflict("The reservation is ${state.name.lowercase(Locale.ROOT)} and cannot be $action.")
        }

        else -> {
            Problem.conflict("The record was changed by another request; read it again and retry.")
        }
    }
