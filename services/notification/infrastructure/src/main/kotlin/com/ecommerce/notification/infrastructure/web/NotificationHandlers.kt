package com.ecommerce.notification.infrastructure.web

import arrow.core.Either
import arrow.core.left
import arrow.core.nonEmptyListOf
import arrow.core.raise.either
import arrow.core.right
import com.ecommerce.notification.application.Caller
import com.ecommerce.notification.application.CallerRole
import com.ecommerce.notification.application.FailedFilter
import com.ecommerce.notification.application.Forbidden
import com.ecommerce.notification.application.ListFailed
import com.ecommerce.notification.application.ListOwn
import com.ecommerce.notification.application.Page
import com.ecommerce.notification.application.PageRequest
import com.ecommerce.notification.application.RetryFailed
import com.ecommerce.notification.application.RetryRefusal
import com.ecommerce.notification.domain.AccountId
import com.ecommerce.notification.domain.Notification
import com.ecommerce.notification.domain.NotificationChannel
import com.ecommerce.notification.domain.NotificationId
import com.ecommerce.notification.domain.NotificationKind
import com.ecommerce.platform.core.problem.Problem
import com.ecommerce.platform.core.result.ValidationError
import com.ecommerce.platform.observability.observedCoRouter
import com.ecommerce.platform.problem.toServerResponse
import com.ecommerce.platform.security.AccountPrincipal
import com.ecommerce.platform.security.Role
import com.ecommerce.platform.security.requireAccount
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.queryParamOrNull
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID
import com.ecommerce.platform.core.values.PageRequest as PageQuery

private const val BAD_REQUEST = 400
private const val CONFLICT = 409

/** The public routes of notification.yaml (the gateway forwards every path under `/api/v1/notifications`). */
fun notificationRoutes(handlers: NotificationHandlers) =
    observedCoRouter {
        GET("/api/v1/notifications/failed", handlers::listFailed)
        GET("/api/v1/notifications", handlers::listOwn)
        POST("/api/v1/notifications/{notificationId}/retry", handlers::retry)
    }

/** The use-case caller of an authenticated request. */
fun AccountPrincipal.toCaller(): Caller =
    Caller(
        AccountId(accountId),
        roles
            .map {
                when (it) {
                    Role.SHOPPER -> CallerRole.SHOPPER
                    Role.OPERATOR -> CallerRole.OPERATOR
                }
            }.toSet(),
    )

/**
 * Handlers of `listOwnNotifications` (shopper), `listFailedNotifications` (operator) and `retryFailedNotification`
 * (operator). An anonymous request is refused first (401); every use case then receives the caller and refuses a
 * caller without its role itself ([Forbidden], answered 403, deny by default), and the shopper listing derives the
 * account filter from the caller. Malformed parameters answer 400 `validation`. Bodies follow the contract's
 * `Notification` schema: no message body and no recipient address, `accountId` for operators only.
 */
class NotificationHandlers(
    private val ownNotifications: ListOwn,
    private val failedNotifications: ListFailed,
    private val retryFailed: RetryFailed,
) {
    suspend fun listOwn(request: ServerRequest): ServerResponse =
        either {
            val caller = requireAccount().bind().toCaller()
            val channel = channel(request).bind()
            val kind = kind(request).bind()
            ownNotifications(caller, page(request).bind(), channel, kind)
                .mapLeft { Problem.forbidden("This operation requires the shopper role.") }
                .bind()
        }.toServerResponse(request) { ok(NotificationViews.page(it, operator = false)) }

    suspend fun listFailed(request: ServerRequest): ServerResponse =
        either {
            val caller = requireAccount().bind().toCaller()
            val filter =
                FailedFilter(
                    accountId = uuid(request, "accountId").bind()?.let(::AccountId),
                    channel = channel(request).bind(),
                    kind = kind(request).bind(),
                    failedFrom = instant(request, "failedFrom").bind(),
                    failedTo = instant(request, "failedTo").bind(),
                )
            failedNotifications(caller, filter, page(request).bind()).mapLeft(::problemOf).bind()
        }.toServerResponse(request) { ok(NotificationViews.page(it, operator = true)) }

    suspend fun retry(request: ServerRequest): ServerResponse =
        either {
            val caller = requireAccount().bind().toCaller()
            val id = parseUuid("notificationId", request.pathVariable("notificationId")).bind()
            retryFailed(caller, NotificationId(id)).mapLeft(::problemOf).bind()
        }.toServerResponse(request) { requeued ->
            ServerResponse
                .status(HttpStatus.ACCEPTED)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValueAndAwait(NotificationViews.of(requeued, operator = true))
        }

    private suspend fun ok(body: Any): ServerResponse =
        ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValueAndAwait(body)

    private companion object {
        fun problemOf(refusal: RetryRefusal): Problem =
            when (refusal) {
                Forbidden -> {
                    Problem.forbidden("This operation requires the operator role.")
                }

                RetryRefusal.NotFound -> {
                    Problem.notFound("No notification exists with the given id.")
                }

                is RetryRefusal.NotFailed -> {
                    Problem.custom(
                        "notification-not-failed",
                        "Notification is not retryable",
                        CONFLICT,
                        "Only failed notifications can be retried; this one is ${refusal.status.wire}.",
                    )
                }
            }

        fun badRequest(
            field: String,
            reason: String,
        ): Problem = Problem.validation(nonEmptyListOf(ValidationError(field, reason)), status = BAD_REQUEST)

        fun page(request: ServerRequest): Either<Problem, PageRequest> =
            PageQuery
                .fromQuery(request.queryParamOrNull("page"), request.queryParamOrNull("size"))
                .map { PageRequest(it.page, it.size) }
                .mapLeft { badRequest(it.field, it.reason) }

        fun channel(request: ServerRequest): Either<Problem, NotificationChannel?> =
            optional(request, "channel") { raw ->
                NotificationChannel.fromWire(raw)?.right() ?: badRequest("channel", "must be email or sms").left()
            }

        fun kind(request: ServerRequest): Either<Problem, NotificationKind?> =
            optional(request, "type") { raw ->
                NotificationKind.fromApiType(raw)?.right() ?: badRequest("type", "is not a notification type").left()
            }

        fun uuid(
            request: ServerRequest,
            name: String,
        ): Either<Problem, UUID?> = optional(request, name) { parseUuid(name, it) }

        fun instant(
            request: ServerRequest,
            name: String,
        ): Either<Problem, Instant?> =
            optional(request, name) { raw ->
                try {
                    Instant.parse(raw).right()
                } catch (_: DateTimeParseException) {
                    badRequest(name, "must be an ISO-8601 date-time").left()
                }
            }

        fun parseUuid(
            name: String,
            raw: String,
        ): Either<Problem, UUID> =
            try {
                UUID.fromString(raw).right()
            } catch (_: IllegalArgumentException) {
                badRequest(name, "must be a UUID").left()
            }

        fun <T : Any> optional(
            request: ServerRequest,
            name: String,
            parse: (String) -> Either<Problem, T>,
        ): Either<Problem, T?> = request.queryParamOrNull(name)?.let(parse) ?: null.right()
    }
}

/** JSON views of notification.yaml `Notification` and `NotificationPage`. */
object NotificationViews {
    fun page(
        page: Page<Notification>,
        operator: Boolean,
    ): Map<String, Any> =
        linkedMapOf(
            "items" to page.items.map { of(it, operator) },
            "page" to page.page,
            "size" to page.size,
            "totalItems" to page.totalItems,
        )

    /** One notification; absent values are omitted, `accountId` only for [operator]s. */
    fun of(
        notification: Notification,
        operator: Boolean,
    ): Map<String, Any> {
        val view = linkedMapOf<String, Any>("id" to notification.id.toString())
        if (operator) view["accountId"] = notification.accountId.toString()
        view["channel"] = notification.channel.wire
        view["type"] = notification.kind.apiType
        view["status"] = notification.status.wire
        view["attempts"] = notification.attempts
        notification.lastFailure?.let { view["lastError"] = it.reason }
        notification.orderId?.let { view["orderId"] = it.toString() }
        view["createdAt"] = notification.createdAt.toString()
        notification.lastAttemptAt?.let { view["lastAttemptAt"] = it.toString() }
        notification.sentAt?.let { view["sentAt"] = it.toString() }
        return view
    }
}
