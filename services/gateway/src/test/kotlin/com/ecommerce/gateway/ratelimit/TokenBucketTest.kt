package com.ecommerce.gateway.ratelimit

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainOnly
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import java.time.Duration

private const val SECOND = 1_000_000_000L
private const val START = 42 * SECOND

/** Takes [count] tokens at [now] and returns the decisions and the final bucket. */
private fun takeMany(
    limit: TokenBucket.Limit,
    count: Int,
    now: Long = START,
    from: TokenBucket = TokenBucket.full(limit, now),
): Pair<TokenBucket, List<TokenBucket.Decision>> {
    var bucket = from
    val decisions =
        (1..count).map {
            val (next, decision) = bucket.take(limit, now)
            bucket = next
            decision
        }
    return bucket to decisions
}

class TokenBucketTest :
    FunSpec({
        val auth = TokenBucket.Limit(10)

        test("a full bucket allows exactly its capacity in a burst, then rejects") {
            val (_, decisions) = takeMany(auth, 11)
            decisions.take(10).forEach { it.shouldBeInstanceOf<TokenBucket.Decision.Allowed>() }
            decisions.last() shouldBe TokenBucket.Decision.Rejected(retryAfterSeconds = 6)
        }

        test("remaining counts down to zero") {
            val (_, decisions) = takeMany(auth, 10)
            decisions.map { (it as TokenBucket.Decision.Allowed).remaining } shouldBe (9 downTo 0).toList()
        }

        test("one token comes back after period / capacity") {
            val (empty, _) = takeMany(auth, 10)
            empty.take(auth, START + 5 * SECOND).second.shouldBeInstanceOf<TokenBucket.Decision.Rejected>()
            empty.take(auth, START + 6 * SECOND + 1).second.shouldBeInstanceOf<TokenBucket.Decision.Allowed>()
        }

        test("Retry-After is the time to the next token, rounded up to whole seconds and at least 1") {
            val (empty, _) = takeMany(auth, 10)
            empty.take(auth, START + SECOND / 2).second shouldBe TokenBucket.Decision.Rejected(6)
            empty.take(auth, START + 3 * SECOND + SECOND / 2).second shouldBe TokenBucket.Decision.Rejected(3)
            empty.take(auth, START + 6 * SECOND - 1).second shouldBe TokenBucket.Decision.Rejected(1)
            val browse = TokenBucket.Limit(600)
            val (drained, _) = takeMany(browse, 600)
            drained.take(browse, START).second shouldBe TokenBucket.Decision.Rejected(1)
        }

        test("refill never exceeds capacity, however long the bucket was idle") {
            checkAll(Arb.int(1..1000), Arb.long(0L..Duration.ofDays(30).toNanos())) { capacity, idle ->
                val limit = TokenBucket.Limit(capacity)
                val (empty, _) = takeMany(limit, capacity)
                val later = START + idle
                val (_, decisions) = takeMany(limit, capacity + 1, later, empty)
                decisions.count { it is TokenBucket.Decision.Allowed } shouldBeLessThanOrEqual capacity
            }
        }

        test("over a whole minute no more than capacity plus the refill are allowed") {
            checkAll(Arb.int(1..600)) { capacity ->
                val limit = TokenBucket.Limit(capacity)
                var bucket = TokenBucket.full(limit, START)
                var allowed = 0
                val step = Duration.ofMinutes(1).toNanos() / (capacity * 4L)
                for (i in 0 until capacity * 4) {
                    val (next, decision) = bucket.take(limit, START + i * step)
                    bucket = next
                    if (decision is TokenBucket.Decision.Allowed) allowed++
                }
                allowed shouldBeLessThanOrEqual 2 * capacity
            }
        }

        test("a clock going backwards does not mint tokens") {
            val (empty, _) = takeMany(auth, 10)
            empty.take(auth, START - 60 * SECOND).second.shouldBeInstanceOf<TokenBucket.Decision.Rejected>()
        }

        test("full is detected once the bucket has refilled") {
            val (empty, _) = takeMany(auth, 10)
            empty.isFull(auth, START + 59 * SECOND) shouldBe false
            empty.isFull(auth, START + 61 * SECOND) shouldBe true
        }

        test("a limit needs a positive capacity and period") {
            shouldThrow<IllegalArgumentException> { TokenBucket.Limit(0) }
            shouldThrow<IllegalArgumentException> { TokenBucket.Limit(1, Duration.ZERO) }
        }

        test("the in-memory limiter keeps one bucket per key and forgets refilled ones") {
            var now = START
            val limiter = InMemoryRateLimiter { now }
            val decisions = (1..11).map { limiter.tryAcquire("auth|address:10.0.0.1", auth) }
            decisions.last().shouldBeInstanceOf<TokenBucket.Decision.Rejected>()
            limiter.tryAcquire("auth|address:10.0.0.2", auth).shouldBeInstanceOf<TokenBucket.Decision.Allowed>()
            limiter.size() shouldBe 2
            now += 61 * SECOND
            limiter.sweep()
            limiter.size() shouldBe 0
            listOf(limiter.tryAcquire("auth|address:10.0.0.1", auth)).map { it::class } shouldContainOnly
                listOf(TokenBucket.Decision.Allowed::class)
        }
    })
