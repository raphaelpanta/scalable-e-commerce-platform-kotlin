package com.ecommerce.cart.infrastructure.web

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
import com.ecommerce.cart.application.ClearAccountCart
import com.ecommerce.cart.application.GetAccountCart
import com.ecommerce.cart.domain.AccountId
import com.ecommerce.platform.core.problem.Problem
import com.ecommerce.platform.problem.toServerResponse
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.buildAndAwait
import java.util.UUID

/**
 * Inbound adapter: the internal API order uses at checkout (contracts/internal/cart-internal.yaml). Reached only
 * with a valid `X-Internal-Token` (platform-core's `InternalTokenWebFilter` answers 401 before this runs).
 */
class InternalCartHandlers(
    private val getAccountCart: GetAccountCart,
    private val clearAccountCart: ClearAccountCart,
) {
    suspend fun get(request: ServerRequest): ServerResponse =
        either {
            val accountId = accountIdOf(request).bind()
            ensureNotNull(getAccountCart(accountId)) { Problem.notFound("No cart exists for the account.") }
        }.toServerResponse(request) { cart ->
            ServerResponse
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValueAndAwait(cart.toJson())
        }

    suspend fun clear(request: ServerRequest): ServerResponse =
        either {
            val accountId = accountIdOf(request).bind()
            clearAccountCart(accountId).mapLeft { it.toProblem() }.bind()
        }.toServerResponse(request) { ServerResponse.noContent().buildAndAwait() }

    private companion object {
        fun accountIdOf(request: ServerRequest): Either<Problem, AccountId> =
            Either
                .catch { AccountId(UUID.fromString(request.pathVariable("accountId"))) }
                .mapLeft { Problem.badRequest("accountId must be a UUID.") }
    }
}
