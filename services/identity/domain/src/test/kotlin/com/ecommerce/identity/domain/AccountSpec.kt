package com.ecommerce.identity.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import java.time.Duration

private val POLICY = ThrottlePolicy()

class AccountSpec :
    FunSpec({
        test("registration creates an unverified shopper at version 0 with nothing to throttle") {
            val id = accountId()
            val registered = Account.register(id, email(), PasswordHash("h"), null, NOW)

            registered.id shouldBe id
            registered.status shouldBe AccountStatus.UNVERIFIED
            registered.roles shouldBe setOf(Role.SHOPPER)
            registered.emailVerified.shouldBeFalse()
            registered.isDeleted.shouldBeFalse()
            registered.createdAt shouldBe NOW
            registered.throttle shouldBe SignInThrottle.CLEAR
            registered.version shouldBe 0
            registered.pseudonym.shouldBeNull()
            registered.deletedAt.shouldBeNull()
            registered.has(Role.SHOPPER).shouldBeTrue()
            registered.has(Role.OPERATOR).shouldBeFalse()
        }

        test("an account always holds a role") {
            shouldThrow<IllegalArgumentException> { account(roles = emptySet()) }
        }

        test("verification activates an unverified account once; a deleted account cannot be verified") {
            val unverified = account(status = AccountStatus.UNVERIFIED)
            val later = NOW.plusSeconds(60)

            val verified = unverified.verify(later).value()

            verified.status shouldBe AccountStatus.ACTIVE
            verified.verifiedAt shouldBe later
            verified.emailVerified.shouldBeTrue()
            verified.version shouldBe unverified.version + 1
            verified.verify(later.plusSeconds(1)).value() shouldBeSameInstanceAs verified
            account(status = AccountStatus.DELETED).verify(later).error() shouldBe IdentityError.InvalidToken
        }

        test("five consecutive failures lock for 15 minutes; fewer never lock") {
            checkAll(Arb.int(1..20), arbSeconds) { failures, offset ->
                val start = NOW.plusSeconds(offset)
                var throttle = SignInThrottle.CLEAR
                repeat(failures) { throttle = throttle.afterFailure(start, POLICY) }

                throttle.failures shouldBe failures
                if (failures < ThrottlePolicy.DEFAULT_MAX_FAILURES) {
                    throttle.lockedUntil.shouldBeNull()
                    throttle.isLocked(start).shouldBeFalse()
                    throttle.retryAfter(start) shouldBe Duration.ZERO
                } else {
                    throttle.lockedUntil shouldBe start.plus(ThrottlePolicy.DEFAULT_LOCK)
                    throttle.isLocked(start).shouldBeTrue()
                    throttle.retryAfter(start) shouldBe Duration.ofMinutes(15)
                    throttle.retryAfter(start.plusSeconds(60)) shouldBe Duration.ofMinutes(14)
                    throttle.isLocked(start.plus(Duration.ofMinutes(15)).minusNanos(1)).shouldBeTrue()
                    throttle.isLocked(start.plus(Duration.ofMinutes(15))).shouldBeFalse()
                    throttle.retryAfter(start.plus(Duration.ofMinutes(15))) shouldBe Duration.ZERO
                }
            }
        }

        test("a failure after an expired lock starts counting again; a running lock keeps counting") {
            val locked = SignInThrottle(5, NOW.plus(Duration.ofMinutes(15)))
            val afterLock = NOW.plus(Duration.ofMinutes(15))

            locked.afterFailure(afterLock, POLICY) shouldBe SignInThrottle(1, null)
            locked.afterFailure(NOW, POLICY) shouldBe SignInThrottle(6, NOW.plus(Duration.ofMinutes(15)))
            SignInThrottle(4, null).afterFailure(NOW, POLICY) shouldBe
                SignInThrottle(5, NOW.plus(Duration.ofMinutes(15)))
            SignInThrottle(0, null).afterFailure(NOW, ThrottlePolicy(1, Duration.ofSeconds(2))) shouldBe
                SignInThrottle(1, NOW.plusSeconds(2))
        }

        test("a throttle policy needs a positive limit and duration; failures are never negative") {
            shouldThrow<IllegalArgumentException> { ThrottlePolicy(0) }
            shouldThrow<IllegalArgumentException> { ThrottlePolicy(1, Duration.ZERO) }
            shouldThrow<IllegalArgumentException> { SignInThrottle(-1, null) }
            ThrottlePolicy(1, Duration.ofNanos(1)).maxFailures shouldBe 1
            SignInThrottle(0, null) shouldBe SignInThrottle.CLEAR
        }

        test("failed sign-ins accumulate on the account and a success resets them") {
            checkAll(Arb.int(1..10)) { failures ->
                var current = account()
                repeat(failures) { current = current.failedSignIn(NOW, POLICY) }
                current.throttle.failures shouldBe failures
                current.version shouldBe failures.toLong()

                val reset = current.succeededSignIn()
                reset.throttle shouldBe SignInThrottle.CLEAR
                reset.version shouldBe failures + 1L
            }
            val clean = account()
            clean.succeededSignIn() shouldBeSameInstanceAs clean
        }

        test("a password change replaces the hash and clears the throttle; renaming keeps everything else") {
            val locked = account().failedSignIn(NOW, ThrottlePolicy(1))
            val changed = locked.changePassword(PasswordHash("new"))

            changed.passwordHash shouldBe PasswordHash("new")
            changed.throttle shouldBe SignInThrottle.CLEAR
            changed.version shouldBe locked.version + 1

            val renamed = changed.rename(DisplayName.of("Grace").value())
            renamed.displayName?.value shouldBe "Grace"
            renamed.version shouldBe changed.version + 1
            renamed.copy(displayName = changed.displayName, version = changed.version) shouldBe changed
            changed.rename(null).displayName.shouldBeNull()
        }

        test("deletion anonymises: placeholder email, no hash, no name, roles and id kept, status deleted") {
            checkAll(arbAccountId, Arb.long(0L..1_000_000L)) { id, offset ->
                val at = NOW.plusSeconds(offset)
                val original = account(id).failedSignIn(NOW, POLICY)
                val pseudonym = Pseudonym.of(id)

                val deleted = original.anonymise(pseudonym, at).value()

                deleted.id shouldBe id
                deleted.email.value shouldBe pseudonym.placeholderEmail
                deleted.passwordHash.shouldBeNull()
                deleted.displayName.shouldBeNull()
                deleted.status shouldBe AccountStatus.DELETED
                deleted.isDeleted.shouldBeTrue()
                deleted.roles shouldBe original.roles
                deleted.deletedAt shouldBe at
                deleted.pseudonym shouldBe pseudonym
                deleted.throttle shouldBe SignInThrottle.CLEAR
                deleted.version shouldBe original.version + 1
                deleted.createdAt shouldBe original.createdAt
                deleted.anonymise(pseudonym, at).error() shouldBe IdentityError.AccountNotFound
            }
        }

        test("an operator cannot delete their own account") {
            val operator = account(roles = setOf(Role.SHOPPER, Role.OPERATOR))
            operator.anonymise(Pseudonym.of(operator.id), NOW).error() shouldBe IdentityError.OperatorCannotSelfDelete
            account(
                status = AccountStatus.UNVERIFIED,
            ).anonymise(Pseudonym("anon-1"), NOW).value().isDeleted.shouldBeTrue()
        }
    })
