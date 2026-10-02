package com.ecommerce.order.domain

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.time.Duration
import java.time.Instant

/** The answer a checkout gave, kept so that a replay returns it unchanged: HTTP-free status and JSON body. */
data class StoredResponse(
    val status: Int,
    val body: String,
)

/**
 * One checkout attempt per (account, key), FR-013 and research section 10. While the first request runs the record
 * is a claim ([response] `null`); it is completed with the order and the answer, kept for 24 hours, and released
 * again when the checkout was refused (no order, no record).
 */
data class IdempotencyRecord(
    val key: IdempotencyKey,
    val accountId: AccountId,
    val requestHash: String,
    val orderId: OrderId?,
    val response: StoredResponse?,
    val createdAt: Instant,
    val expiresAt: Instant,
) {
    /** What a request with [requestHash] and this key gets now. */
    fun replayFor(requestHash: String): Either<OrderError, Replay> =
        when {
            requestHash != this.requestHash -> OrderError.IdempotencyKeyReuse.left()
            orderId == null || response == null -> Replay.InProgress.right()
            else -> Replay.Completed(orderId, response).right()
        }

    companion object {
        /** How long a completed record answers replays. */
        val RETENTION: Duration = Duration.ofHours(24)

        /** After this long an unfinished claim is considered abandoned (a crashed request) and can be taken over. */
        val CLAIM_TIMEOUT: Duration = Duration.ofMinutes(2)

        /** The claim of a first request. */
        fun claim(
            key: IdempotencyKey,
            accountId: AccountId,
            requestHash: String,
            now: Instant,
        ): IdempotencyRecord = IdempotencyRecord(key, accountId, requestHash, null, null, now, now.plus(RETENTION))
    }
}

/** What a repeated request with a known key receives. */
sealed interface Replay {
    /** The first request is still running. */
    data object InProgress : Replay

    /** The stored answer of the first request. */
    data class Completed(
        val orderId: OrderId,
        val response: StoredResponse,
    ) : Replay
}
