package com.ecommerce.payment.application

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant

private val RETENTION: Duration = Duration.ofMinutes(33)

private fun cancelledAt(recordedAt: Instant): CancelledOrderRecord =
    CancelledOrderRecord(newOrder(), ADA_CONTACT, recordedAt)

private fun InMemoryCancellations.holding(vararg records: CancelledOrderRecord): InMemoryCancellations =
    apply { records.forEach { stored[it.orderId] = it } }

/** PurgeCancelledOrders: a cancelled order and its contact snapshot are kept for the late-charge window only. */
class PurgeCancelledOrdersSpec :
    FunSpec({
        test("orders recorded before the retention are forgotten, the others are kept") {
            val old = cancelledAt(NOW.minus(RETENTION).minusMillis(1))
            val edge = cancelledAt(NOW.minus(RETENTION))
            val recent = cancelledAt(NOW.minusSeconds(1))
            val cancellations = InMemoryCancellations().holding(old, edge, recent)

            PurgeCancelledOrders(cancellations, fixedClock(), RETENTION)() shouldBe 1L

            cancellations.stored shouldContainExactly mapOf(edge.orderId to edge, recent.orderId to recent)
        }

        test("nothing past the retention forgets nothing") {
            val recent = cancelledAt(NOW)
            val cancellations = InMemoryCancellations().holding(recent)

            PurgeCancelledOrders(cancellations, fixedClock(), RETENTION)() shouldBe 0L

            cancellations.stored shouldContainExactly mapOf(recent.orderId to recent)
        }

        test("the retention must be positive") {
            listOf(Duration.ZERO, Duration.ofSeconds(-1)).forEach { retention ->
                shouldThrow<IllegalArgumentException> {
                    PurgeCancelledOrders(InMemoryCancellations(), fixedClock(), retention)
                }
            }
        }
    })
