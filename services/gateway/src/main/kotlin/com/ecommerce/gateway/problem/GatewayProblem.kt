package com.ecommerce.gateway.problem

import org.springframework.http.HttpStatus

/** Base of every problem type URI (docs/service-conventions.md section 3). */
const val PROBLEM_TYPE_BASE = "https://ecommerce.example/problems/"

/**
 * The errors the gateway itself originates, as RFC 9457 problem types. 502 and 504 reuse the `unavailable` type of
 * 503: for the client all three mean that the target service could not answer.
 */
enum class GatewayProblem(
    val status: HttpStatus,
    slug: String,
    val title: String,
    val defaultDetail: String,
) {
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "validation", "Bad request", "The request is not valid."),
    UNAUTHORIZED(
        HttpStatus.UNAUTHORIZED,
        "unauthorized",
        "Unauthorized",
        "A valid bearer token is required.",
    ),
    FORBIDDEN(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "The caller lacks the role this route requires."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "not-found", "Not found", "No route matches this method and path."),
    PAYLOAD_TOO_LARGE(
        HttpStatus.CONTENT_TOO_LARGE,
        "payload-too-large",
        "Payload too large",
        "The request body exceeds the limit of this route.",
    ),
    THROTTLED(
        HttpStatus.TOO_MANY_REQUESTS,
        "throttled",
        "Too many requests",
        "Rate limit exceeded. Try again later.",
    ),
    INTERNAL(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "internal",
        "Internal error",
        "The gateway could not process the request.",
    ),
    BAD_GATEWAY(
        HttpStatus.BAD_GATEWAY,
        "unavailable",
        "Service unavailable",
        "The target service returned an invalid response.",
    ),
    UNAVAILABLE(
        HttpStatus.SERVICE_UNAVAILABLE,
        "unavailable",
        "Service unavailable",
        "The target service is unavailable.",
    ),
    GATEWAY_TIMEOUT(
        HttpStatus.GATEWAY_TIMEOUT,
        "unavailable",
        "Service unavailable",
        "The target service did not answer in time.",
    ),
    ;

    val type: String = PROBLEM_TYPE_BASE + slug
}

/** Raised by gateway filters and rendered by [ProblemWebExceptionHandler]; [headers] are added to the response. */
class GatewayProblemException(
    val problem: GatewayProblem,
    val detail: String = problem.defaultDetail,
    val headers: Map<String, String> = emptyMap(),
    cause: Throwable? = null,
) : RuntimeException(detail, cause)
