package com.ecommerce.notification.application

import com.ecommerce.notification.domain.AccountId
import com.ecommerce.notification.domain.DedupeKey
import com.ecommerce.notification.domain.DeliveryAttempt
import com.ecommerce.notification.domain.DeliveryStatus
import com.ecommerce.notification.domain.EventId
import com.ecommerce.notification.domain.MessageContent
import com.ecommerce.notification.domain.Notification
import com.ecommerce.notification.domain.NotificationId
import com.ecommerce.notification.domain.Recipient
import kotlinx.coroutines.yield
import java.time.Duration
import java.time.Instant

/** The failure a [Gate] injects. */
class PortFailure : RuntimeException("port failed")

/**
 * Every fake port passes through the gate: it really suspends (so that the coroutine resume paths run) and, when
 * [failAt] is the number of the current call, throws [PortFailure] after resuming.
 */
class Gate(
    var failAt: Int = -1,
) {
    var calls = 0

    suspend fun pass() {
        yield()
        calls++
        if (calls == failAt) throw PortFailure()
    }
}

/** In-memory [NotificationRepository] with the semantics of the R2DBC adapter. */
class InMemoryNotifications(
    private val gate: Gate = Gate(),
) : NotificationRepository {
    val stored = linkedMapOf<NotificationId, Notification>()
    val attempts = mutableListOf<DeliveryAttempt>()
    val forgotten = mutableListOf<AccountId>()

    override suspend fun keysForEvent(eventId: EventId): Set<DedupeKey> {
        gate.pass()
        return stored.values
            .filter { it.sourceEventId == eventId }
            .map { it.dedupeKey }
            .toSet()
    }

    override suspend fun insertIfAbsent(notification: Notification): Boolean {
        gate.pass()
        if (stored.values.any { it.dedupeKey == notification.dedupeKey }) return false
        stored[notification.id] = notification
        return true
    }

    override suspend fun findById(id: NotificationId): Notification? {
        gate.pass()
        return stored[id]
    }

    override suspend fun update(
        notification: Notification,
        expected: DeliveryStatus,
    ): Boolean {
        gate.pass()
        if (stored[notification.id]?.status != expected) return false
        stored[notification.id] = notification
        return true
    }

    override suspend fun claimDue(
        now: Instant,
        leaseUntil: Instant,
        limit: Int,
    ): List<Notification> {
        gate.pass()
        val due =
            stored.values
                .filter { it.status == DeliveryStatus.QUEUED && it.nextAttemptAt?.let { at -> at <= now } == true }
                .sortedBy { it.nextAttemptAt }
                .take(limit)
        due.forEach { stored[it.id] = it.copy(nextAttemptAt = leaseUntil) }
        return due
    }

    override suspend fun recordAttempt(attempt: DeliveryAttempt) {
        gate.pass()
        attempts += attempt
    }

    override suspend fun queuedFor(accountId: AccountId): List<Notification> {
        gate.pass()
        return stored.values.filter { it.accountId == accountId && it.status == DeliveryStatus.QUEUED }
    }

    override suspend fun forgetRecipient(accountId: AccountId) {
        gate.pass()
        forgotten += accountId
        stored.values.filter { it.accountId == accountId }.forEach {
            stored[it.id] = it.copy(recipient = null, content = MessageContent(it.content.subject, ""))
        }
    }

    override suspend fun listOwn(
        filter: OwnFilter,
        page: PageRequest,
    ): Page<Notification> {
        gate.pass()
        return page(
            stored.values
                .filter { it.accountId == filter.accountId }
                .filter { filter.channel == null || it.channel == filter.channel }
                .filter { filter.kind == null || it.kind == filter.kind }
                .sortedByDescending { it.createdAt },
            page,
        )
    }

    override suspend fun listFailed(
        filter: FailedFilter,
        page: PageRequest,
    ): Page<Notification> {
        gate.pass()
        return page(
            stored.values
                .filter { it.status == DeliveryStatus.FAILED }
                .filter { filter.accountId == null || it.accountId == filter.accountId }
                .filter { filter.channel == null || it.channel == filter.channel }
                .filter { filter.kind == null || it.kind == filter.kind }
                .filter { filter.failedFrom == null || it.failedAt?.let { at -> at >= filter.failedFrom } == true }
                .filter { filter.failedTo == null || it.failedAt?.let { at -> at < filter.failedTo } == true }
                .sortedByDescending { it.failedAt },
            page,
        )
    }

    private fun page(
        all: List<Notification>,
        request: PageRequest,
    ): Page<Notification> =
        Page(all.drop(request.offset.toInt()).take(request.size), request.page, request.size, all.size.toLong())
}

/** In-memory [RecipientReadModel]. */
class InMemoryRecipients(
    private val gate: Gate = Gate(),
) : RecipientReadModel {
    val stored = linkedMapOf<AccountId, Recipient>()

    override suspend fun find(accountId: AccountId): Recipient? {
        gate.pass()
        return stored[accountId]
    }

    override suspend fun save(recipient: Recipient) {
        gate.pass()
        stored[recipient.accountId] = recipient
    }
}

/** A [Clock] the test moves by hand. */
class ManualClock(
    var now: Instant = Instant.parse("2026-10-02T10:00:00Z"),
) : Clock {
    override fun now(): Instant = now

    fun advance(duration: Duration) {
        now = now.plus(duration)
    }
}

/** Runs blocks directly (after suspending once) and counts them. */
class DirectTransactions(
    private val gate: Gate = Gate(),
) : Transactions {
    var runs = 0

    override suspend fun <T> run(block: suspend () -> T): T {
        gate.pass()
        runs++
        return block()
    }
}

/** A channel that answers with the next scripted result (the last one repeats) and records what it got. */
class ScriptedChannel(
    vararg results: SendResult,
) : EmailSenderPort,
    SmsSenderPort {
    private val script = results.toMutableList()
    val sent = mutableListOf<OutgoingMessage>()
    var gate = Gate()

    override suspend fun send(message: OutgoingMessage): SendResult {
        gate.pass()
        sent += message
        return if (script.size > 1) script.removeAt(0) else script.first()
    }
}

/** Records published outcomes. */
class RecordingOutcomes(
    private val gate: Gate = Gate(),
) : DeliveryOutcomePublisher {
    val sent = mutableListOf<Notification>()
    val failed = mutableListOf<Notification>()

    override suspend fun sent(notification: Notification) {
        gate.pass()
        sent += notification
    }

    override suspend fun failed(notification: Notification) {
        gate.pass()
        failed += notification
    }
}
