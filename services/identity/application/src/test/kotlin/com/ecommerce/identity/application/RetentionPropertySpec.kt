package com.ecommerce.identity.application

import com.ecommerce.identity.application.IdentityArbs.PROPERTIES
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.yield
import java.time.Duration
import java.time.Instant

private const val MAX_RETENTION_SECONDS = 90L * 24 * 3600
private const val MAX_DELETED = 1_000_000L

/** Records the cut-offs it is asked for and answers the given counts. */
private class RecordingRetention(
    private val counts: PurgeReport,
) : RetentionRepository {
    val cutoffs = mutableMapOf<String, Instant>()

    override suspend fun deleteTokensExpiredBefore(cutoff: Instant): Long {
        yield()
        cutoffs["tokens"] = cutoff
        return counts.tokens
    }

    override suspend fun deleteSessionsEndedBefore(cutoff: Instant): Long {
        yield()
        cutoffs["sessions"] = cutoff
        return counts.sessions
    }

    override suspend fun deleteThrottlesIdleSince(
        cutoff: Instant,
        now: Instant,
    ): Long {
        yield()
        cutoffs["throttles"] = cutoff
        cutoffs["throttlesNow"] = now
        return counts.throttles
    }

    override suspend fun deletePhoneVerificationsExpiredBefore(cutoff: Instant): Long {
        yield()
        cutoffs["phoneVerifications"] = cutoff
        return counts.phoneVerifications
    }
}

private val retentionPolicies: Arb<RetentionPolicy> =
    Arb.bind(
        IdentityArbs.duration(MAX_RETENTION_SECONDS),
        IdentityArbs.duration(MAX_RETENTION_SECONDS),
        IdentityArbs.duration(MAX_RETENTION_SECONDS),
        IdentityArbs.duration(MAX_RETENTION_SECONDS),
        ::RetentionPolicy,
    )

private val deletedCounts: Arb<PurgeReport> =
    Arb.bind(
        Arb.long(0L..MAX_DELETED),
        Arb.long(0L..MAX_DELETED),
        Arb.long(0L..MAX_DELETED),
        Arb.long(0L..MAX_DELETED),
        ::PurgeReport,
    )

/** T128 / data-model section 5: the purge deletes exactly what each retention allows at the current time. */
class RetentionPropertySpec :
    FunSpec({
        test("the defaults are those of data-model section 5") {
            RetentionPolicy() shouldBe
                RetentionPolicy(Duration.ofDays(7), Duration.ofDays(30), Duration.ofDays(1), Duration.ofDays(1))
        }

        test("each kind of record is cut off its own retention before now, and the report adds them up") {
            checkAll(PROPERTIES, IdentityArbs.instant, retentionPolicies, deletedCounts) { now, policy, deleted ->
                val repository = RecordingRetention(deleted)

                val report = PurgeRetainedData(repository, FakeClock(now), policy)()

                repository.cutoffs shouldBe
                    mapOf(
                        "tokens" to now.minus(policy.tokenRetention),
                        "sessions" to now.minus(policy.sessionRetention),
                        "throttles" to now.minus(policy.throttleIdle),
                        "throttlesNow" to now,
                        "phoneVerifications" to now.minus(policy.phoneVerificationRetention),
                    )
                report shouldBe deleted
                report.total shouldBe deleted.tokens + deleted.sessions + deleted.throttles + deleted.phoneVerifications
            }
        }

        test("a negative retention is refused, whichever it is, and zero is allowed") {
            checkAll(PROPERTIES, Arb.long(1L..MAX_RETENTION_SECONDS)) { seconds ->
                val negative = Duration.ofSeconds(-seconds)

                shouldThrow<IllegalArgumentException> { RetentionPolicy(tokenRetention = negative) }
                shouldThrow<IllegalArgumentException> { RetentionPolicy(sessionRetention = negative) }
                shouldThrow<IllegalArgumentException> { RetentionPolicy(throttleIdle = negative) }
                shouldThrow<IllegalArgumentException> { RetentionPolicy(phoneVerificationRetention = negative) }
                RetentionPolicy(Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO).throttleIdle shouldBe
                    Duration.ZERO
            }
        }
    })
