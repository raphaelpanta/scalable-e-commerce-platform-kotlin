package com.ecommerce.identity.application

import com.ecommerce.identity.application.IdentityArbs.PROPERTIES
import com.ecommerce.identity.domain.IdentityError
import com.ecommerce.identity.domain.OpaqueToken
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeUnique
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import java.time.Duration

private const val MAX_ROTATIONS = 8
private const val MAX_GAP_SECONDS = 6 * 3600L
private const val MAX_LIFETIME_DAYS = 60L
private const val PERCENT = 100L

/**
 * T122: refresh tokens rotate on every use, a spent one presented again revokes its whole session (token theft), and
 * a session refreshes only while it lasts, for any number of rotations, gaps and lifetimes.
 */
class RefreshRotationPropertySpec :
    FunSpec({
        test("every refresh rotates the token; presenting any spent one revokes the session for good") {
            checkAll(
                PROPERTIES,
                Arb.int(1..MAX_ROTATIONS),
                Arb.int(0..MAX_ROTATIONS),
                IdentityArbs.duration(MAX_GAP_SECONDS),
            ) { rotations, seed, gap ->
                val harness = Harness()
                val ada = harness.shopper()
                val refresh = RefreshSession(harness.store)
                val signedIn = SignIn(harness.store)(Credentials(ADA, PASSWORD, "s")).value()
                val issued = mutableListOf<OpaqueToken>(signedIn.refreshToken)
                repeat(rotations) {
                    harness.clock.advance(gap)
                    issued += refresh(issued.last().value).value().refreshToken
                }

                issued.map { it.value }.shouldBeUnique()
                val session = harness.sessions.of(ada.id).single()
                session.refreshTokenHash shouldBe issued.last().hash()
                session.rotatedAt shouldBe harness.clock.instant
                session.revokedAt.shouldBeNull()
                harness.signer.grants
                    .map { it.sessionId }
                    .toSet() shouldBe setOf(session.id)

                harness.clock.advance(Duration.ofSeconds(1))
                val spent = issued[seed % rotations]
                refresh(spent.value).error() shouldBe IdentityError.InvalidRefreshToken

                harness.sessions
                    .of(ada.id)
                    .single()
                    .revokedAt shouldBe harness.clock.instant
                refresh(issued.last().value).error() shouldBe IdentityError.InvalidRefreshToken
                refresh(spent.value).error() shouldBe IdentityError.InvalidRefreshToken
                harness.sessions
                    .of(ada.id)
                    .single()
                    .revokedAt shouldBe harness.clock.instant
            }
        }

        test("a session refreshes only before it expires, and expiry revokes nothing") {
            checkAll(
                PROPERTIES,
                Arb.long(1L..MAX_LIFETIME_DAYS * 24 * 3600),
                Arb.long(0L..2 * PERCENT),
            ) { lifetimeSeconds, percent ->
                val lifetime = Duration.ofSeconds(lifetimeSeconds)
                val harness = Harness(IdentityPolicies(sessionLifetime = lifetime))
                val ada = harness.shopper()
                val pair = SignIn(harness.store)(Credentials(ADA, PASSWORD, "s")).value()
                val elapsed = lifetime.multipliedBy(percent).dividedBy(PERCENT)
                harness.clock.advance(elapsed)

                val result = RefreshSession(harness.store)(pair.refreshToken.value)

                if (elapsed < lifetime) {
                    result.value().refreshToken shouldBe FakeSecrets.token(1)
                } else {
                    result.error() shouldBe IdentityError.InvalidRefreshToken
                }
                harness.sessions
                    .of(ada.id)
                    .single()
                    .revokedAt
                    .shouldBeNull()
                harness.sessions
                    .of(ada.id)
                    .single()
                    .expiresAt shouldBe NOW.plus(lifetime)
            }
        }

        test("signing out or resetting the password ends every refresh, whatever was rotated before") {
            checkAll(PROPERTIES, Arb.int(0..MAX_ROTATIONS), IdentityArbs.password) { rotations, newPassword ->
                val harness = Harness()
                val ada = harness.shopper()
                val refresh = RefreshSession(harness.store)
                var current = SignIn(harness.store)(Credentials(ADA, PASSWORD, "s")).value().refreshToken
                repeat(rotations) { current = refresh(current.value).value().refreshToken }
                val other = SignIn(harness.store)(Credentials(ADA, PASSWORD, "t")).value().refreshToken
                val first = harness.sessions.of(ada.id).first()

                SignOut(harness.store)(ada.id, first.id)
                refresh(current.value).error() shouldBe IdentityError.InvalidRefreshToken
                refresh(other.value).value()

                RequestPasswordReset(harness.store)(ADA).value()
                val reset = FakeSecrets.token(harness.secrets.tokens - 1)
                CompletePasswordReset(harness.store)(ResetCompletion(reset.value, newPassword)).value()
                harness.sessions.of(ada.id).forEach { it.revokedAt shouldBe NOW }
            }
        }
    })
