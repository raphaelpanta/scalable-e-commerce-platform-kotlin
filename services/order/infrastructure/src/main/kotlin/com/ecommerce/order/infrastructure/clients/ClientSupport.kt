package com.ecommerce.order.infrastructure.clients

import com.ecommerce.platform.core.problem.Problem
import com.ecommerce.platform.http.ProblemClientException
import com.ecommerce.platform.problem.ProblemException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.web.reactive.function.client.WebClientException

private val log: Logger = LoggerFactory.getLogger("com.ecommerce.order.infrastructure.clients")

/** Money as every internal contract writes it. */
data class MoneyJson(
    val amountMinor: Long,
    val currency: String,
)

/** The 503 answered when a dependency the checkout needs cannot be reached or fails. */
internal fun unavailable(
    service: String,
    cause: Throwable? = null,
): ProblemException = ProblemException(Problem.unavailable("The $service service is unavailable."), cause = cause)

/** Null for a 404 answer (an absent resource); any other error answer is a 503 problem naming [service]. */
internal fun <T> absentIfNotFound(
    service: String,
    failure: ProblemClientException,
): T? = if (failure.status == NOT_FOUND) null else throw unavailable(service, failure)

private const val NOT_FOUND = 404

/** Runs [call]; a connection failure or timeout becomes a 503 problem naming [service]. */
internal suspend fun <T> required(
    service: String,
    call: suspend () -> T,
): T =
    try {
        call()
    } catch (failure: WebClientException) {
        throw unavailable(service, failure)
    }

/**
 * Runs a call whose failure is tolerated because an event converges on the same state (commit, release, clear):
 * the failure is logged without any personal data and swallowed.
 */
internal suspend fun tolerated(
    operation: String,
    call: suspend () -> Any?,
) {
    try {
        when (val result = call()) {
            is ProblemClientException -> {
                log.warn(
                    "{} answered {}; the order events will converge",
                    operation,
                    result.status,
                )
            }

            else -> {
                Unit
            }
        }
    } catch (failure: WebClientException) {
        log.warn("{} failed; the order events will converge", operation, failure)
    }
}
