package com.ecommerce.notification.application

import com.ecommerce.notification.domain.DeliveryAttempt
import com.ecommerce.notification.domain.DeliveryStatus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.pair
import io.kotest.property.checkAll
import java.time.Duration

private val RETENTION: Duration = Duration.ofDays(90)

/** (age in hours, status) of one stored notification. */
private val stored = Arb.list(Arb.pair(Arb.long(0L, 24L * 200), Arb.enum<DeliveryStatus>()), 0..12)

/** A world holding one notification per (age, status), with one attempt row each. */
private suspend fun worldWith(notifications: List<Pair<Long, DeliveryStatus>>): World {
    val world = World()
    notifications.forEach { (hours, status) ->
        world.produce(shipped(source()))
        val latest =
            world.notifications.stored.values
                .last()
        val aged = latest.copy(status = status, createdAt = world.clock.now.minus(Duration.ofHours(hours)))
        world.notifications.stored[aged.id] = aged
        world.notifications.attempts += DeliveryAttempt(aged.id, 1, aged.createdAt, null)
    }
    return world
}

/**
 * Data-model section 5 and FR-007: terminal notifications older than the retention go with their attempt history;
 * queued ones (still being delivered) and recent ones stay.
 */
class RetentionSpec :
    FunSpec({
        test("terminal notifications older than the retention are deleted with their attempts; the rest stays") {
            checkAll(stored, Arb.int(1, 5)) { notifications, batch ->
                val world = worldWith(notifications)
                val cutoff = world.clock.now.minus(RETENTION)
                val expected =
                    world.notifications.stored.values
                        .filter { it.status == DeliveryStatus.QUEUED || it.createdAt >= cutoff }
                val policy = RetentionPolicy(RETENTION, batch)
                val purge = PurgeExpiredNotifications(world.notifications, world.clock, policy)

                val purged = purge()

                world.notifications.stored.values
                    .toList() shouldBe expected
                purged shouldBe notifications.size - expected.size
                world.notifications.attempts.map { it.notificationId } shouldBe expected.map { it.id }
                world.notifications.purges.forEach { (at, limit) ->
                    at shouldBe cutoff
                    limit shouldBe batch
                }
                world.notifications.purges.size shouldBe purged / batch + 1
                purge() shouldBe 0
            }
        }

        test("the defaults are 90 days in batches of 500, and invalid policies are refused") {
            RetentionPolicy() shouldBe RetentionPolicy(Duration.ofDays(90), 500)
            RetentionPolicy(Duration.ZERO, 1).batchSize shouldBe 1
            shouldThrow<IllegalArgumentException> { RetentionPolicy(Duration.ofDays(-1)) }
            shouldThrow<IllegalArgumentException> { RetentionPolicy(batchSize = 0) }
            val world = World()
            world.produce(shipped(source()))
            PurgeExpiredNotifications(world.notifications, world.clock)() shouldBe 0
            world.notifications.purges shouldBe listOf(world.clock.now.minus(Duration.ofDays(90)) to 500)
        }
    })
