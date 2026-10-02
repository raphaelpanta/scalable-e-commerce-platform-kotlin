package com.ecommerce.cart.infrastructure.web

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import com.ecommerce.cart.application.AddLine
import com.ecommerce.cart.application.CartIdentity
import com.ecommerce.cart.application.ClearCart
import com.ecommerce.cart.application.GetCart
import com.ecommerce.cart.application.MergeCarts
import com.ecommerce.cart.application.RemoveLine
import com.ecommerce.cart.application.UpdateLineQuantity
import com.ecommerce.cart.domain.AccountId
import com.ecommerce.cart.domain.LineId
import com.ecommerce.cart.domain.ProductId
import com.ecommerce.platform.core.problem.Problem
import com.ecommerce.platform.core.values.SecretToken
import com.ecommerce.platform.problem.toServerResponse
import com.ecommerce.platform.security.Role
import com.ecommerce.platform.security.currentAccount
import com.ecommerce.platform.security.requireAccount
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.awaitBodyOrNull
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.buildAndAwait
import java.util.UUID

/** Request header of the anonymous cart token (cart.yaml `CartToken`). */
const val CART_TOKEN_HEADER: String = "X-Cart-Token"

/**
 * Inbound adapter: the public cart API (contracts/openapi/cart.yaml). The caller is the account of a valid bearer
 * token (its account cart; `X-Cart-Token` is ignored), else the holder of `X-Cart-Token` (looked up by the token's
 * SHA-256 hash, never stored or logged in clear), else nobody (an empty cart to read, a new anonymous cart and token
 * on the first `addCartLine`). Every cart answer carries the opaque `revision` and `Cache-Control: no-store`.
 */
@Suppress("LongParameterList") // one use case per operation of cart.yaml
class CartHandlers(
    private val getCart: GetCart,
    private val addLine: AddLine,
    private val updateLineQuantity: UpdateLineQuantity,
    private val removeLine: RemoveLine,
    private val clearCart: ClearCart,
    private val mergeCarts: MergeCarts,
) {
    suspend fun get(request: ServerRequest): ServerResponse =
        getCart(identityOf(request)).mapLeft { it.toProblem() }.toServerResponse(request) { ok(it.toJson()) }

    suspend fun addLine(request: ServerRequest): ServerResponse =
        either {
            val body = ensureNotNull(request.awaitBodyOrNull<AddLineRequest>()) { Problem.badRequest(BODY_REQUIRED) }
            val productId = ensureNotNull(body.productId) { Problem.badRequest("productId is required.") }
            val quantity = ensureNotNull(body.quantity) { Problem.badRequest("quantity is required.") }
            addLine(identityOf(request), ProductId(productId), quantity).mapLeft { it.toProblem() }.bind()
        }.toServerResponse(request) { added ->
            ServerResponse
                .status(HttpStatus.CREATED)
                .noStore()
                .headers { headers -> added.issuedToken?.let { headers.set(CART_TOKEN_HEADER, it.value) } }
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValueAndAwait(added.cart.toJson())
        }

    suspend fun updateLine(request: ServerRequest): ServerResponse =
        either {
            val lineId = lineIdOf(request).bind()
            val body = ensureNotNull(request.awaitBodyOrNull<UpdateLineRequest>()) { Problem.badRequest(BODY_REQUIRED) }
            val quantity = ensureNotNull(body.quantity) { Problem.badRequest("quantity is required.") }
            updateLineQuantity(identityOf(request), lineId, quantity).mapLeft { it.toProblem() }.bind()
        }.toServerResponse(request) { ok(it.toJson()) }

    suspend fun removeLine(request: ServerRequest): ServerResponse =
        either {
            val lineId = lineIdOf(request).bind()
            removeLine(identityOf(request), lineId).mapLeft { it.toProblem() }.bind()
        }.toServerResponse(request) { ok(it.toJson()) }

    suspend fun clear(request: ServerRequest): ServerResponse =
        clearCart(identityOf(request)).mapLeft { it.toProblem() }.toServerResponse(request) {
            ServerResponse.noContent().header(HttpHeaders.CACHE_CONTROL, NO_STORE).buildAndAwait()
        }

    suspend fun merge(request: ServerRequest): ServerResponse =
        either {
            val account = requireAccount().bind()
            ensure(account.has(Role.SHOPPER) || account.has(Role.OPERATOR)) {
                Problem.forbidden("Merging a cart requires the shopper or operator role.")
            }
            val token =
                ensureNotNull(request.cartToken()) { Problem.badRequest("Header $CART_TOKEN_HEADER is required.") }
            mergeCarts(AccountId(account.accountId), SecretToken.sha256Hex(token)).mapLeft { it.toProblem() }.bind()
        }.toServerResponse(request) { ok(it.toJson()) }

    private companion object {
        const val BODY_REQUIRED = "A JSON request body is required."
        const val NO_STORE = "no-store"

        suspend fun identityOf(request: ServerRequest): CartIdentity =
            currentAccount()?.let { CartIdentity.Account(AccountId(it.accountId)) }
                ?: request.cartToken()?.let { CartIdentity.Anonymous(SecretToken.sha256Hex(it)) }
                ?: CartIdentity.Unidentified

        fun ServerRequest.cartToken(): String? = headers().firstHeader(CART_TOKEN_HEADER)?.takeIf { it.isNotBlank() }

        fun lineIdOf(request: ServerRequest): Either<Problem, LineId> =
            Either
                .catch { LineId(UUID.fromString(request.pathVariable("lineId"))) }
                .mapLeft { Problem.badRequest("lineId must be a UUID.") }

        fun ServerResponse.BodyBuilder.noStore(): ServerResponse.BodyBuilder =
            header(HttpHeaders.CACHE_CONTROL, NO_STORE)

        suspend fun ok(body: Any): ServerResponse =
            ServerResponse
                .ok()
                .noStore()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValueAndAwait(body)
    }
}
