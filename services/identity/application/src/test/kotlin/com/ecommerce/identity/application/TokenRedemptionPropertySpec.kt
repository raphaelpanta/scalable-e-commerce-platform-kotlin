package com.ecommerce.identity.application

import com.ecommerce.identity.application.IdentityArbs.PROPERTIES
import com.ecommerce.identity.domain.AccountStatus
import com.ecommerce.identity.domain.IdentityError
import com.ecommerce.identity.domain.TokenPurpose
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import java.time.Duration

private val VERIFICATION_TTL: Duration = Duration.ofHours(24)
private val RESET_TTL: Duration = Duration.ofHours(1)
private const val MAX_RESET_REQUESTS = 4
private const val MAX_RAW_TOKEN = 60

/**
 * T122 / FR-004, FR-006: verification and reset tokens are single use and time limited for any delay, any number of
 * re-issues and any presented text; a spent reset replaces the password and revokes every session.
 */
class TokenRedemptionPropertySpec :
    FunSpec({
        test("a verification token works once, and only before its 24 hours run out") {
            checkAll(
                PROPERTIES,
                IdentityArbs.email,
                IdentityArbs.password,
                IdentityArbs.duration(2 * VERIFICATION_TTL.seconds),
            ) { address, password, delay ->
                val harness = Harness()
                RegisterAccount(harness.store)(Registration(address, password, null)).value()
                val id =
                    harness.accounts.accounts.keys
                        .single()
                harness.clock.advance(delay)
                val valid = delay < VERIFICATION_TTL

                val first = VerifyEmail(harness.store)(FakeSecrets.token(0).value)

                if (valid) {
                    first.value()
                    harness.accounts[id].status shouldBe AccountStatus.ACTIVE
                    harness.accounts[id].verifiedAt shouldBe harness.clock.instant
                    harness.events.verified shouldHaveSize 1
                } else {
                    first.error() shouldBe IdentityError.InvalidToken
                    harness.accounts[id].status shouldBe AccountStatus.UNVERIFIED
                    harness.events.verified.shouldBeEmpty()
                }
                VerifyEmail(harness.store)(FakeSecrets.token(0).value).error() shouldBe IdentityError.InvalidToken
                harness.events.verified.size shouldBe if (valid) 1 else 0
            }
        }

        test("only the newest reset token works, once, within the hour; it replaces the password and ends sessions") {
            checkAll(
                PROPERTIES,
                IdentityArbs.email,
                IdentityArbs.password,
                Arb.int(1..MAX_RESET_REQUESTS),
                IdentityArbs.duration(2 * RESET_TTL.seconds),
            ) { address, newPassword, requests, delay ->
                val harness = Harness()
                val ada = harness.shopper(address)
                SignIn(harness.store)(Credentials(address, PASSWORD, "s")).value()
                repeat(requests) { RequestPasswordReset(harness.store)(address.uppercase()).value() }
                harness.clock.advance(delay)
                val complete = CompletePasswordReset(harness.store)

                (1 until requests).forEach { older ->
                    complete(ResetCompletion(FakeSecrets.token(older).value, newPassword)).error() shouldBe
                        IdentityError.InvalidToken
                }
                val newest = complete(ResetCompletion(FakeSecrets.token(requests).value, newPassword))

                harness.events.resets shouldHaveSize requests
                harness.tokens.of(ada.id, TokenPurpose.PASSWORD_RESET) shouldHaveSize requests
                if (delay < RESET_TTL) {
                    newest.value()
                    harness.accounts[ada.id].passwordHash?.value shouldBe "hash:$newPassword"
                    harness.sessions
                        .of(ada.id)
                        .single()
                        .revokedAt shouldBe harness.clock.instant
                    SignIn(harness.store)(Credentials(address, PASSWORD, "t")).error() shouldBe
                        IdentityError.InvalidCredentials
                    SignIn(harness.store)(Credentials(address, newPassword, "u")).value()
                } else {
                    newest.error() shouldBe IdentityError.InvalidToken
                    harness.accounts[ada.id] shouldBe ada
                    harness.sessions
                        .of(ada.id)
                        .single()
                        .revokedAt shouldBe null
                }
                complete(ResetCompletion(FakeSecrets.token(requests).value, newPassword)).error() shouldBe
                    IdentityError.InvalidToken
            }
        }

        test("any text that is not an issued token is refused alike and changes nothing") {
            checkAll(PROPERTIES, Arb.string(0..MAX_RAW_TOKEN), IdentityArbs.password) { raw, newPassword ->
                val harness = Harness()
                RegisterAccount(harness.store)(Registration(ADA, PASSWORD, null)).value()
                val before = harness.accounts.accounts.toMap()
                val tokensBefore = harness.tokens.tokens.toMap()

                VerifyEmail(harness.store)(raw).error() shouldBe IdentityError.InvalidToken
                CompletePasswordReset(harness.store)(ResetCompletion(raw, newPassword)).error() shouldBe
                    IdentityError.InvalidToken

                harness.accounts.accounts shouldBe before
                harness.tokens.tokens shouldBe tokensBefore
                harness.events.verified.shouldBeEmpty()
            }
        }

        test("a token of one purpose never redeems the other") {
            checkAll(PROPERTIES, IdentityArbs.email, IdentityArbs.password) { address, password ->
                val harness = Harness()
                RegisterAccount(harness.store)(Registration(address, password, null)).value()
                val id =
                    harness.accounts.accounts.keys
                        .single()
                harness.accounts.store(harness.accounts[id].verify(NOW).value())
                RequestPasswordReset(harness.store)(address).value()

                CompletePasswordReset(harness.store)(ResetCompletion(FakeSecrets.token(0).value, password))
                    .error() shouldBe IdentityError.InvalidToken
                VerifyEmail(harness.store)(FakeSecrets.token(1).value).error() shouldBe IdentityError.InvalidToken
            }
        }
    })
