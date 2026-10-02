package com.ecommerce.identity.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.property.Arb
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import java.time.Duration
import java.util.UUID

class TokensSpec :
    FunSpec({
        test("verification tokens live 24 hours and reset tokens one hour") {
            TokenPurpose.EMAIL_VERIFICATION.ttl shouldBe Duration.ofHours(24)
            TokenPurpose.PASSWORD_RESET.ttl shouldBe Duration.ofHours(1)
            TokenPurpose.entries.map { it.code } shouldBe listOf("email_verification", "password_reset")
        }

        test("a token is usable until it expires and only once") {
            checkAll(Arb.enum<TokenPurpose>(), Arb.long(0L..200_000L)) { purpose, elapsed ->
                val owner = accountId()
                val issued = OneTimeToken.issue(owner, purpose, token().hash(), NOW)
                val at = NOW.plusSeconds(elapsed)

                issued.accountId shouldBe owner
                issued.purpose shouldBe purpose
                issued.issuedAt shouldBe NOW
                issued.expiresAt shouldBe NOW.plus(purpose.ttl)
                issued.usedAt.shouldBeNull()
                val valid = at.isBefore(NOW.plus(purpose.ttl))
                issued.isUsable(at) shouldBe valid
                if (valid) {
                    val used = issued.redeem(at).value()
                    used.usedAt shouldBe at
                    used.isUsable(at).shouldBeFalse()
                    used.redeem(at).error() shouldBe IdentityError.InvalidToken
                } else {
                    issued.redeem(at).error() shouldBe IdentityError.InvalidToken
                }
            }
        }

        test("a token is not usable at the exact expiry instant") {
            val issued = OneTimeToken.issue(accountId(), TokenPurpose.PASSWORD_RESET, token().hash(), NOW)
            issued.isUsable(NOW.plus(Duration.ofHours(1)).minusNanos(1)).shouldBeTrue()
            issued.isUsable(NOW.plus(Duration.ofHours(1))).shouldBeFalse()
        }

        test("a session starts for 30 days and rotates its refresh token") {
            val id = SessionId(UUID.randomUUID())
            val owner = accountId()
            val first = token('a').hash()
            val second = token('b').hash()
            val session = SessionRecord.start(id, owner, first, NOW)

            session.id shouldBe id
            session.accountId shouldBe owner
            session.expiresAt shouldBe NOW.plus(Duration.ofDays(30))
            session.isActive(NOW).shouldBeTrue()
            session.isActive(NOW.plus(Duration.ofDays(30))).shouldBeFalse()
            session.isActive(NOW.plus(Duration.ofDays(30)).minusNanos(1)).shouldBeTrue()

            val later = NOW.plusSeconds(60)
            val rotated = session.rotate(first, second, later).value()
            rotated.refreshTokenHash shouldBe second
            rotated.rotatedAt shouldBe later
            rotated.rotate(first, token('c').hash(), later).error() shouldBe IdentityError.RefreshTokenReused
            SessionRecord.start(id, owner, first, NOW, Duration.ofSeconds(5)).expiresAt shouldBe NOW.plusSeconds(5)
        }

        test("a revoked or expired session refuses every refresh token") {
            val first = token('a').hash()
            val session = SessionRecord.start(SessionId(UUID.randomUUID()), accountId(), first, NOW)
            val revoked = session.revoke(NOW.plusSeconds(1))

            revoked.revokedAt shouldBe NOW.plusSeconds(1)
            revoked.isActive(NOW.plusSeconds(2)).shouldBeFalse()
            revoked.revoke(NOW.plusSeconds(9)) shouldBeSameInstanceAs revoked
            revoked.rotate(first, token('b').hash(), NOW.plusSeconds(2)).error() shouldBe
                IdentityError.InvalidRefreshToken
            session.rotate(first, token('b').hash(), NOW.plus(Duration.ofDays(31))).error() shouldBe
                IdentityError.InvalidRefreshToken
        }
    })
