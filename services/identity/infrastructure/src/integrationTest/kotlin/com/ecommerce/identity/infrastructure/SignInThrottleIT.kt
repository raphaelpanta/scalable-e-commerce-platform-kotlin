package com.ecommerce.identity.infrastructure

import com.ecommerce.identity.domain.Digests
import com.ecommerce.platform.core.problem.ProblemType
import com.ecommerce.platform.testing.ProblemAssertions.expectProblem
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.comparables.shouldBeBetween
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executors

private const val LOCK_AFTER = 5
private const val PARALLEL = 12
private val LOCK: Duration = Duration.ofMinutes(15)
private val QUERY_TIMEOUT: Duration = Duration.ofSeconds(10)

/**
 * T133 and T160 (FR-006): failed sign-ins are counted under a row lock, so parallel wrong passwords cannot slip past
 * the limit of 5, and an email without an account is throttled after 5 failures exactly like an account.
 */
class SignInThrottleIT : IdentityIntegrationTest() {
    private fun throttleFailures(key: String): Int? =
        database
            .sql("SELECT failures FROM sign_in_source WHERE source_hash = :hash")
            .bind("hash", Digests.sha256Hex("sign-in-$key"))
            .map { row -> checkNotNull(row.get("failures", Int::class.javaObjectType)) }
            .one()
            .block(QUERY_TIMEOUT)

    @Test
    fun `parallel wrong passwords are all counted and lock the account and the source after five`() {
        val account = registerAndVerify()
        val source = freshSource()

        val pool = Executors.newFixedThreadPool(PARALLEL)
        val statuses =
            try {
                pool
                    .invokeAll(
                        (1..PARALLEL).map { attempt ->
                            Callable {
                                signIn(account.email, "Wrong-$attempt-password", source)
                                    .expectBody()
                                    .returnResult()
                                    .status
                                    .value()
                            }
                        },
                    ).map { it.get() }
            } finally {
                pool.shutdown()
            }

        statuses.forEach { it shouldBeIn listOf(UNAUTHORIZED, TOO_MANY) }
        val failures = statuses.count { it == UNAUTHORIZED }
        failures shouldBeGreaterThanOrEqual LOCK_AFTER
        accountColumn(account.email, "failed_sign_ins") shouldBe failures.toString()
        accountColumn(account.email, "locked_until").shouldNotBeNull()
        throttleFailures("source:$source") shouldBe failures
        signIn(account.email, PASSWORD).expectProblem(ProblemType.THROTTLED)
        signIn(freshEmail(), PASSWORD, source).expectProblem(ProblemType.THROTTLED)
    }

    @Test
    fun `an unknown email is throttled after five failures with the same answer as an account`() {
        val unknown = freshEmail()
        val known = registerAndVerify()
        repeat(LOCK_AFTER) {
            signIn(unknown, PASSWORD).expectProblem(ProblemType.UNAUTHORIZED)
            signIn(known.email, "Wrong-$it-password").expectProblem(ProblemType.UNAUTHORIZED)
        }

        val forUnknown = signIn(" ${unknown.uppercase()}", PASSWORD)
        val forKnown = signIn(known.email, PASSWORD)

        listOf(forUnknown, forKnown)
            .map { response ->
                response.expectHeader().value("Retry-After") { it.toLong().shouldBeBetween(1L, LOCK.seconds) }
                response.json(TOO_MANY).minus(setOf("correlationId", "instance"))
            }.let { (unknownBody, knownBody) -> unknownBody shouldBe knownBody }
        throttleFailures("email:$unknown") shouldBe LOCK_AFTER
        throttleFailures("email:${known.email}") shouldBe null
    }
}
