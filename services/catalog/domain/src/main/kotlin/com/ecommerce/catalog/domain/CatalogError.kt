package com.ecommerce.catalog.domain

import arrow.core.NonEmptyList

/** One rejected input: the request [field] it belongs to and a short, client-safe [reason]. */
data class FieldIssue(
    val field: String,
    val reason: String,
)

/** Why a catalogue operation was refused; the web layer maps each case to its RFC 9457 problem. */
sealed interface CatalogError {
    /** Input that breaks one or more value-object rules (every broken rule is listed). */
    data class Invalid(
        val issues: NonEmptyList<FieldIssue>,
    ) : CatalogError

    /** The product does not exist, or is withdrawn and hidden from the caller. */
    data class ProductNotFound(
        val productId: ProductId,
    ) : CatalogError

    /** The category does not exist. */
    data class CategoryNotFound(
        val categoryId: CategoryId,
    ) : CatalogError

    /** The reservation does not exist. */
    data class ReservationNotFound(
        val reservationId: ReservationId,
    ) : CatalogError

    /** The product is withdrawn already. */
    data class AlreadyWithdrawn(
        val productId: ProductId,
    ) : CatalogError

    /** The category is withdrawn already. */
    data class CategoryAlreadyWithdrawn(
        val categoryId: CategoryId,
    ) : CatalogError

    /** The product is on sale already, so there is nothing to reinstate. */
    data class NotWithdrawn(
        val productId: ProductId,
    ) : CatalogError

    /** The category is active already, so there is nothing to reinstate. */
    data class CategoryNotWithdrawn(
        val categoryId: CategoryId,
    ) : CatalogError

    /** The product cannot be reinstated while its category is withdrawn (or beneath a withdrawn one). */
    data class CategoryWithdrawn(
        val productId: ProductId,
        val categoryId: CategoryId,
    ) : CatalogError

    /** Another product carries the same SKU. */
    data class DuplicateSku(
        val sku: Sku,
    ) : CatalogError

    /** Another category of the same parent carries the same name. */
    data class DuplicateCategoryName(
        val name: CategoryName,
    ) : CatalogError

    /** The placement of a category would create a cycle or exceed the maximum depth. */
    data class InvalidHierarchy(
        val reason: String,
    ) : CatalogError

    /** A product holds at most [Product.MAX_IMAGES] images. */
    data class TooManyImages(
        val productId: ProductId,
    ) : CatalogError

    /** At least one line cannot be reserved; every short line is listed in request order. */
    data class InsufficientStock(
        val lines: NonEmptyList<UnavailableLine>,
    ) : CatalogError

    /** A stock adjustment of [delta] would take the [available] quantity below zero (below the reserved units). */
    data class StockBelowReserved(
        val productId: ProductId,
        val delta: Int,
        val available: Int,
    ) : CatalogError

    /** The reservation is [state] and cannot be [action] (commit after release, release after commit). */
    data class IllegalTransition(
        val reservationId: ReservationId,
        val state: ReservationState,
        val action: String,
    ) : CatalogError

    /** The caller lacks the operator role required by [action]. */
    data class Forbidden(
        val action: String,
    ) : CatalogError

    /** The record changed concurrently; the caller may retry. */
    data object ConcurrentUpdate : CatalogError
}
