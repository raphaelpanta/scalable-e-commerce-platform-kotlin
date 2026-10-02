package com.ecommerce.identity.infrastructure.web

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import arrow.core.toNonEmptyListOrNull
import com.ecommerce.identity.domain.FieldError
import com.ecommerce.identity.domain.IdentityError
import com.ecommerce.platform.core.problem.Problem
import com.ecommerce.platform.core.result.ValidationError
import com.ecommerce.platform.problem.ProblemResponses
import com.ecommerce.platform.security.AccountPrincipal
import com.ecommerce.platform.security.requireAccount
import org.springframework.http.HttpHeaders
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.awaitBodyOrNull
import java.util.UUID

private const val BAD_REQUEST = 400
private const val NANOS_PER_SECOND = 1_000_000_000L

/** A refused request: the problem to answer with and extra response headers (`Retry-After` of a 429). */
class Refusal(
    val problem: Problem,
    val headers: Map<String, String> = emptyMap(),
)

/** Answers with the refusal on the left or [onSuccess] applied to the value on the right. */
suspend fun <T> Either<Refusal, T>.respond(
    request: ServerRequest,
    onSuccess: suspend (T) -> ServerResponse,
): ServerResponse = fold({ ProblemResponses.of(request, it.problem, it.headers) }, { onSuccess(it) })

/** The public answer to an identity error (identity.yaml). */
fun <T> Either<IdentityError, T>.refused(): Either<Refusal, T> = mapLeft { it.toRefusal() }

/** The internal answer (identity-internal.yaml): an unknown account is 404, not 401. */
fun <T> Either<IdentityError, T>.refusedInternally(): Either<Refusal, T> =
    mapLeft {
        if (it == IdentityError.AccountNotFound) Refusal(Problem.notFound("Account not found.")) else it.toRefusal()
    }

/** The authenticated account of the request (401 when anonymous). */
suspend fun principal(): Either<Refusal, AccountPrincipal> = requireAccount().mapLeft(::Refusal)

/** The JSON body of the request as [T], or a 400 when it is absent. */
suspend inline fun <reified T : Any> ServerRequest.jsonBody(): Either<Refusal, T> =
    awaitBodyOrNull<T>()?.right() ?: Refusal(Problem.badRequest("A JSON request body is required.")).left()

/** The value of the required body member [field], or a 400 naming it. */
fun <T : Any> T?.required(field: String): Either<Refusal, T> = this?.right() ?: badRequest(field, "is required").left()

/** Path variable [name] as a UUID, or a 400. */
fun ServerRequest.uuid(name: String): Either<Refusal, UUID> =
    Either.catch { UUID.fromString(pathVariable(name)) }.mapLeft { badRequest(name, "must be a UUID") }

/** A 400 `validation` problem on [field]. */
fun badRequest(
    field: String,
    reason: String,
): Refusal =
    Refusal(Problem.validation(arrow.core.nonEmptyListOf(ValidationError(field, reason)), status = BAD_REQUEST))

/** The RFC 9457 problem of an identity error; a 429 carries `Retry-After` (whole seconds, at least 1). */
fun IdentityError.toRefusal(): Refusal =
    when (this) {
        is IdentityError.Invalid -> Refusal(validation(errors))
        is IdentityError.Malformed -> Refusal(validation(errors, BAD_REQUEST))
        is IdentityError.Throttled -> throttled(this)
        else -> Refusal(checkNotNull(PROBLEMS[this]) { "no problem for $this" })
    }

/** The problems of the identity errors that carry no data. */
private val PROBLEMS: Map<IdentityError, Problem> =
    mapOf(
        IdentityError.InvalidCredentials to Problem.unauthorized("Invalid credentials."),
        IdentityError.EmailNotVerified to Problem.forbidden("Email address is not verified."),
        IdentityError.InvalidToken to validation(listOf(FieldError("token", "is invalid, used or expired"))),
        IdentityError.InvalidRefreshToken to Problem.unauthorized("The refresh token is invalid or expired."),
        IdentityError.RefreshTokenReused to Problem.unauthorized("The refresh token is invalid or expired."),
        IdentityError.AccountNotFound to Problem.unauthorized("The account is not available."),
        IdentityError.AddressNotFound to Problem.notFound("Address not found."),
        IdentityError.TooManyAddresses to
            validation(listOf(FieldError("address", "an account keeps at most 10 addresses"))),
        IdentityError.OperatorCannotSelfDelete to Problem.forbidden("Operator accounts cannot delete themselves."),
        IdentityError.SmsRequiresVerifiedPhone to
            validation(listOf(FieldError("channels", "sms requires a verified phone number"))),
        IdentityError.NoPendingPhoneVerification to validation(listOf(FieldError("code", "no code is pending"))),
        IdentityError.WrongCode to validation(listOf(FieldError("code", "does not match the code that was sent"))),
        IdentityError.CodeExpired to validation(listOf(FieldError("code", "has expired; request a new code"))),
        IdentityError.TooManyCodeAttempts to
            validation(listOf(FieldError("code", "too many attempts; request a new code"))),
        IdentityError.SigningUnavailable to Problem.unavailable("No signing key is available."),
        IdentityError.SmsUnavailable to Problem.unavailable("The SMS channel is unavailable."),
        IdentityError.ConcurrentUpdate to Problem.conflict("The account was changed by another request; retry."),
    )

private fun throttled(error: IdentityError.Throttled): Refusal {
    val seconds = maxOf(1L, (error.retryAfter.toNanos() + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND)
    return Refusal(
        Problem.throttled("Too many attempts. Try again later."),
        mapOf(HttpHeaders.RETRY_AFTER to seconds.toString()),
    )
}

private fun validation(
    errors: List<FieldError>,
    status: Int? = null,
): Problem {
    val list = checkNotNull(errors.map { ValidationError(it.field, it.reason) }.toNonEmptyListOrNull()) { "no errors" }
    return if (status == null) Problem.validation(list) else Problem.validation(list, status = status)
}
