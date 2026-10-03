package com.ecommerce.identity.application

import com.ecommerce.identity.application.IdentityArbs.PROPERTIES
import com.ecommerce.identity.domain.IdentityError
import com.ecommerce.identity.domain.SignInThrottle
import com.ecommerce.identity.domain.ThrottlePolicy
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.time.Duration
import java.util.concurrent.Executors

private const val MAX_ATTEMPTS = 12
private const val MAX_PARALLEL = 16
private const val SOURCE = "198.51.100.7"

/** A source limit no test reaches, so that only the account (or unknown-email) throttle is exercised. */
private val UNLIMITED: ThrottlePolicy = ThrottlePolicy(Int.MAX_VALUE, Duration.ofMinutes(1))

private fun wrong(attempt: Int): String = "Wrong-$attempt-passphrase"

/**
 * T122 / FR-006: properties of the sign-in throttle over generated policies, addresses and attempt counts: the lock
 * and its expiry, unknown emails answering exactly like accounts, and failures that are all counted when attempts run
 * concurrently (T133).
 */
class SignInPropertySpec :
    FunSpec({
        test("below the limit a wrong password is a 401; the limit-th failure locks for exactly the lock duration") {
            checkAll(PROPERTIES, IdentityArbs.throttlePolicy, IdentityArbs.email, Arb.int(1..MAX_ATTEMPTS)) {
                policy,
                address,
                attempts,
                ->
                val harness = Harness(IdentityPolicies(accountThrottle = policy, sourceThrottle = UNLIMITED))
                val ada = harness.shopper(address)
                val signIn = SignIn(harness.store)

                val answers = (0 until attempts).map { signIn(Credentials(address, wrong(it), "s$it")).error() }

                answers.forEachIndexed { attempt, answer ->
                    answer shouldBe
                        if (attempt < policy.maxFailures) {
                            IdentityError.InvalidCredentials
                        } else {
                            IdentityError.Throttled(policy.lockDuration)
                        }
                }
                val lockedUntil = if (attempts >= policy.maxFailures) NOW.plus(policy.lockDuration) else null
                val failures = minOf(attempts, policy.maxFailures)
                harness.accounts[ada.id].throttle shouldBe SignInThrottle(failures, lockedUntil)
                harness.hasher.verified shouldBe failures
            }
        }

        test("a lock holds until its last instant, even for the right password, and then a success clears it") {
            checkAll(PROPERTIES, IdentityArbs.throttlePolicy, Arb.long(0L..Long.MAX_VALUE)) { policy, seed ->
                val harness = Harness(IdentityPolicies(accountThrottle = policy, sourceThrottle = UNLIMITED))
                val ada = harness.shopper()
                val signIn = SignIn(harness.store)
                repeat(policy.maxFailures) { signIn(Credentials(ADA, wrong(it), "s$it")).error() }
                val remaining = Duration.ofSeconds(1 + seed % policy.lockDuration.seconds)

                harness.clock.advance(policy.lockDuration.minus(remaining))
                signIn(Credentials(ADA, PASSWORD, "fresh")).error() shouldBe IdentityError.Throttled(remaining)
                harness.clock.advance(remaining)
                signIn(Credentials(ADA, PASSWORD, "fresh")).value()

                harness.accounts[ada.id].throttle shouldBe SignInThrottle.CLEAR
                harness.hasher.verified shouldBe policy.maxFailures + 1
            }
        }

        test("an expired lock restarts the count: one more failure does not lock again below the limit") {
            checkAll(PROPERTIES, IdentityArbs.throttlePolicy) { policy ->
                val harness = Harness(IdentityPolicies(accountThrottle = policy, sourceThrottle = UNLIMITED))
                val ada = harness.shopper()
                val signIn = SignIn(harness.store)
                repeat(policy.maxFailures) { signIn(Credentials(ADA, wrong(it), "s$it")).error() }
                harness.clock.advance(policy.lockDuration)

                signIn(Credentials(ADA, wrong(0), "fresh")).error() shouldBe IdentityError.InvalidCredentials

                val expected =
                    if (policy.maxFailures == 1) {
                        SignInThrottle(1, harness.clock.instant.plus(policy.lockDuration))
                    } else {
                        SignInThrottle(1, null)
                    }
                harness.accounts[ada.id].throttle shouldBe expected
            }
        }

        test("an unknown email gets exactly the answers of an account with a wrong password, in any case") {
            checkAll(
                PROPERTIES,
                IdentityArbs.throttlePolicy,
                IdentityArbs.email,
                IdentityArbs.email,
                Arb.int(1..MAX_ATTEMPTS),
            ) { policy, known, unknownLocal, attempts ->
                val unknown = "other-$unknownLocal"
                val harness = Harness(IdentityPolicies(accountThrottle = policy, sourceThrottle = UNLIMITED))
                harness.shopper(known)
                val signIn = SignIn(harness.store)

                repeat(attempts) { attempt ->
                    val typed = if (attempt % 2 == 0) unknown.uppercase() else " $unknown "
                    val forUnknown = signIn(Credentials(typed, PASSWORD, "u$attempt")).error()
                    val forKnown = signIn(Credentials(known, wrong(attempt), "k$attempt")).error()
                    forUnknown shouldBe forKnown
                }
                harness.throttles[ThrottleKey.email(unknown)].failures shouldBe minOf(attempts, policy.maxFailures)
                harness.throttles[ThrottleKey.email(known)] shouldBe SignInThrottle.CLEAR
            }
        }

        test("concurrent wrong passwords are all counted, so the limit locks the account and the source (T133)") {
            checkAll(PROPERTIES, IdentityArbs.throttlePolicy, Arb.int(1..MAX_PARALLEL)) { policy, parallel ->
                val harness = Harness(IdentityPolicies(accountThrottle = policy, sourceThrottle = policy))
                val ada = harness.shopper()
                val signIn = SignIn(harness.store)

                val answers =
                    Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { oneThread ->
                        withContext(oneThread) {
                            (0 until parallel)
                                .map { async { signIn(Credentials(ADA, wrong(it), SOURCE)).error() } }
                                .awaitAll()
                        }
                    }

                val counted = answers.count { it == IdentityError.InvalidCredentials }
                answers.filterNot { it == IdentityError.InvalidCredentials }.forEach {
                    it.shouldBeInstanceOf<IdentityError.Throttled>()
                }
                counted shouldBeGreaterThanOrEqual minOf(parallel, policy.maxFailures)
                harness.accounts[ada.id].throttle.failures shouldBe counted
                harness.throttles[ThrottleKey.source(SOURCE)].failures shouldBe counted
                val locked = counted >= policy.maxFailures
                (harness.accounts[ada.id].throttle.lockedUntil != null) shouldBe locked
                val next = signIn(Credentials(ADA, PASSWORD, "fresh"))
                if (locked) {
                    next.error() shouldBe IdentityError.Throttled(policy.lockDuration)
                } else {
                    next.value()
                }
            }
        }

        test("a success below the limit resets the account and the source, under their locks") {
            checkAll(PROPERTIES, IdentityArbs.throttlePolicy, Arb.int(0..MAX_ATTEMPTS)) { policy, seed ->
                val failures = seed % policy.maxFailures
                val harness = Harness(IdentityPolicies(accountThrottle = policy, sourceThrottle = policy))
                val ada = harness.shopper()
                val signIn = SignIn(harness.store)
                repeat(failures) { signIn(Credentials(ADA, wrong(it), SOURCE)).error() }

                signIn(Credentials(ADA, PASSWORD, SOURCE)).value()

                harness.accounts[ada.id].throttle shouldBe SignInThrottle.CLEAR
                harness.throttles[ThrottleKey.source(SOURCE)] shouldBe SignInThrottle.CLEAR
                val resets = if (failures > 0) 1 else 0
                harness.accounts.lockedChanges shouldBe failures + resets
                harness.throttles.changes shouldBe failures + resets
            }
        }

        test("throttle keys ignore the case and blanks of an email, never mix kinds and never show the value") {
            checkAll(PROPERTIES, IdentityArbs.email, IdentityArbs.source) { address, source ->
                val key = ThrottleKey.email(address)

                ThrottleKey.email("  ${address.uppercase()} ") shouldBe key
                ThrottleKey.email("  ${address.uppercase()} ").hashCode() shouldBe key.hashCode()
                key.value shouldBe "email:$address"
                ThrottleKey.source(source).value shouldBe "source:$source"
                ThrottleKey.source(address) shouldNotBe key
                (key as Any) shouldNotBe address
                key.toString() shouldBe "ThrottleKey(email)"
                ThrottleKey.source(source).toString() shouldBe "ThrottleKey(source)"
                key.toString() shouldNotContain address
            }
        }
    })
