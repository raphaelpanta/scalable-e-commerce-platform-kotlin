package com.ecommerce.catalog.infrastructure.web

import arrow.core.Either
import arrow.core.left
import arrow.core.nonEmptyListOf
import arrow.core.right
import com.ecommerce.catalog.application.Caller
import com.ecommerce.catalog.application.CallerRole
import com.ecommerce.catalog.domain.AccountId
import com.ecommerce.catalog.domain.PageRequest
import com.ecommerce.platform.core.problem.Problem
import com.ecommerce.platform.core.result.ValidationError
import com.ecommerce.platform.core.values.Uuids
import com.ecommerce.platform.security.Role
import com.ecommerce.platform.security.currentAccount
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.awaitBodyOrNull
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import java.util.UUID

/** The caller of the request: the account of a valid bearer token with its catalogue roles, or anonymous. */
suspend fun callerOf(): Caller =
    currentAccount()?.let { account ->
        Caller(
            AccountId(account.accountId),
            account.roles
                .map { role ->
                    when (role) {
                        Role.SHOPPER -> CallerRole.SHOPPER
                        Role.OPERATOR -> CallerRole.OPERATOR
                    }
                }.toSet(),
        )
    } ?: Caller.ANONYMOUS

/** A 400 `validation` problem naming the rejected request [field]. */
fun badRequest(
    field: String,
    reason: String,
): Problem =
    Problem.validation(
        nonEmptyListOf(ValidationError(field, reason)),
        "Request parameter $field $reason.",
        BAD_REQUEST,
    )

/** The path variable [name] as a UUID, or a 400 problem. */
fun ServerRequest.uuidPath(name: String): Either<Problem, UUID> =
    Uuids.parse(pathVariable(name), name).mapLeft { badRequest(it.field, it.reason) }

/** The optional query parameter [name] as a UUID, or a 400 problem. */
fun ServerRequest.uuidQuery(name: String): Either<Problem, UUID?> =
    queryParam(name)
        .orElse(null)
        ?.let { raw -> Uuids.parse(raw, name).mapLeft { badRequest(it.field, it.reason) } }
        ?: null.right()

/** The optional query parameter [name] as a boolean (default false), or a 400 problem. */
fun ServerRequest.booleanQuery(name: String): Either<Problem, Boolean> =
    when (queryParam(name).orElse(null)?.lowercase()) {
        null, "false" -> false.right()
        "true" -> true.right()
        else -> badRequest(name, "must be true or false").left()
    }

/** `page` and `size` (0-based, 1..100, default 20), or a 400 problem. */
fun ServerRequest.pageRequest(): Either<Problem, PageRequest> {
    val page = queryParam("page").orElse(null)
    val size = queryParam("size").orElse(null)
    val pageNumber = page?.toIntOrNull()
    val sizeNumber = size?.toIntOrNull()
    return when {
        page != null && pageNumber == null -> {
            badRequest("page", "must be an integer").left()
        }

        size != null && sizeNumber == null -> {
            badRequest("size", "must be an integer").left()
        }

        else -> {
            PageRequest
                .of(pageNumber ?: 0, sizeNumber ?: PageRequest.DEFAULT_SIZE)
                .mapLeft { badRequest(it.field, it.reason) }
        }
    }
}

/** The JSON body as [T], or a 400 problem when it is absent. */
suspend inline fun <reified T : Any> ServerRequest.jsonBody(): Either<Problem, T> =
    awaitBodyOrNull<T>()?.right() ?: Problem.badRequest("A JSON request body is required.").left()

/** [value] when present, or a 400 problem saying that [field] is required. */
fun <T : Any> required(
    value: T?,
    field: String,
): Either<Problem, T> = value?.right() ?: badRequest(field, "is required").left()

/** `200 OK` with a JSON [body]. */
suspend fun ok(body: Any): ServerResponse = json(HttpStatus.OK, body)

/** A JSON [body] with [status]. */
suspend fun json(
    status: HttpStatus,
    body: Any,
): ServerResponse = ServerResponse.status(status).contentType(MediaType.APPLICATION_JSON).bodyValueAndAwait(body)
