package com.ecommerce.catalog.infrastructure.web

import arrow.core.Either
import arrow.core.raise.either
import com.ecommerce.catalog.application.CommitReservation
import com.ecommerce.catalog.application.ReleaseReservation
import com.ecommerce.catalog.application.ReserveStock
import com.ecommerce.catalog.domain.OrderId
import com.ecommerce.catalog.domain.ProductId
import com.ecommerce.catalog.domain.ReservationId
import com.ecommerce.platform.core.problem.Problem
import com.ecommerce.platform.correlation.CorrelationIds
import com.ecommerce.platform.problem.toServerResponse
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.buildAndAwait

/**
 * Inbound adapter: the reservation endpoints of catalog-internal.yaml, called by order at checkout. Reached only
 * with a valid `X-Internal-Token` (platform-core's `InternalTokenWebFilter` answers 401 before this runs). Every
 * error is a problem; validation problems are 400 on this API.
 */
class ReservationHandlers(
    private val reserveStock: ReserveStock,
    private val commitReservation: CommitReservation,
    private val releaseReservation: ReleaseReservation,
) {
    /** `reserveStock`: 201 for a new reservation, 200 for the existing one of the order, 409 `insufficient-stock`. */
    suspend fun reserve(request: ServerRequest): ServerResponse =
        either {
            val body = request.jsonBody<ReserveStockRequest>().bind()
            val orderId = OrderId(required(body.orderId, "orderId").bind())
            val lines = linesOf(required(body.lines, "lines").bind()).bind()
            reserveStock(orderId, lines, CorrelationIds.from(request)).mapLeft { it.toProblem(BAD_REQUEST) }.bind()
        }.toServerResponse(request) { reserved ->
            json(if (reserved.created) HttpStatus.CREATED else HttpStatus.OK, reserved.reservation.toJson())
        }

    /** `commitReservation`: 204 now or before, 409 after a release, 404 when unknown. */
    suspend fun commit(request: ServerRequest): ServerResponse =
        either {
            val reservationId = ReservationId(request.uuidPath("reservationId").bind())
            commitReservation(reservationId, CorrelationIds.from(request)).mapLeft { it.toProblem(BAD_REQUEST) }.bind()
        }.toServerResponse(request) { ServerResponse.noContent().buildAndAwait() }

    /** `releaseReservation`: 204 now or before, 409 after a commit, 404 when unknown. */
    suspend fun release(request: ServerRequest): ServerResponse =
        either {
            val reservationId = ReservationId(request.uuidPath("reservationId").bind())
            releaseReservation(reservationId, CorrelationIds.from(request)).mapLeft { it.toProblem(BAD_REQUEST) }.bind()
        }.toServerResponse(request) { ServerResponse.noContent().buildAndAwait() }

    private companion object {
        fun linesOf(lines: List<StockLineRequest?>): Either<Problem, List<Pair<ProductId, Int>>> =
            either {
                lines.mapIndexed { index, line ->
                    val productId = required(line?.productId, "lines[$index].productId").bind()
                    val quantity = required(line?.quantity, "lines[$index].quantity").bind()
                    ProductId(productId) to quantity
                }
            }
    }
}
