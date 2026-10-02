package com.ecommerce.identity.application

import com.ecommerce.identity.domain.AccountId
import com.ecommerce.identity.domain.AccountStatus
import com.ecommerce.identity.domain.IdentityError
import com.ecommerce.identity.domain.Role
import com.ecommerce.identity.domain.SessionId
import com.ecommerce.identity.domain.SignInThrottle
import com.ecommerce.identity.domain.ThrottlePolicy
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.time.Duration
import java.util.UUID

private const val SOURCE = "203.0.113.7"

private fun credentials(
    password: String = PASSWORD,
    address: String = ADA,
    source: String = SOURCE,
) = Credentials(address, password, source)

class SessionsTest :
    FunSpec({
        test("a verified shopper signs in: a 15 min access token for a new 30 day session") {
            val harness = Harness()
            val ada = harness.shopper()

            val pair = SignIn(harness.store)(credentials()).value()

            pair.accessToken.value shouldBe "jwt-1"
            pair.accessToken.expiresIn shouldBe Duration.ofMinutes(15)
            pair.refreshToken shouldBe FakeSecrets.token(0)
            val session = harness.sessions.of(ada.id).single()
            session.refreshTokenHash shouldBe FakeSecrets.token(0).hash()
            session.expiresAt shouldBe NOW.plus(Duration.ofDays(30))
            val grant = harness.signer.grants.single()
            grant shouldBe AccessGrant(ada.id, setOf(Role.SHOPPER), session.id, NOW, Duration.ofMinutes(15))
            harness.accounts[ada.id] shouldBe ada
            harness.sourceThrottles.saves shouldBe 0
            pair.toString() shouldNotContain "jwt-1"
            credentials().toString() shouldNotContain PASSWORD
        }

        test("unknown email, malformed email and wrong password are the same 401 and still cost a verification") {
            val harness = Harness()
            harness.shopper()
            val signIn = SignIn(harness.store)

            signIn(credentials(address = "nobody@example.test")).error() shouldBe IdentityError.InvalidCredentials
            signIn(credentials(address = "not-an-email")).error() shouldBe IdentityError.InvalidCredentials
            signIn(credentials(password = "Wrong-passphrase!")).error() shouldBe IdentityError.InvalidCredentials

            harness.hasher.verified shouldBe 3
            harness.sourceThrottles.throttles[SOURCE] shouldBe SignInThrottle(3, null)
            harness.accounts.accounts.values
                .single()
                .throttle shouldBe SignInThrottle(1, null)
            harness.sessions.sessions.values
                .shouldBeEmpty()
        }

        test("the fifth consecutive failure locks the account for 15 minutes, even for the right password") {
            val harness = Harness()
            val ada = harness.shopper()
            val signIn = SignIn(harness.store)

            repeat(ThrottlePolicy.DEFAULT_MAX_FAILURES) { attempt ->
                signIn(credentials(password = "Wrong-$attempt", source = "s$attempt")).error() shouldBe
                    IdentityError.InvalidCredentials
            }
            harness.clock.advance(Duration.ofMinutes(5))

            signIn(credentials(source = "fresh")).error() shouldBe IdentityError.Throttled(Duration.ofMinutes(10))
            harness.hasher.verified shouldBe ThrottlePolicy.DEFAULT_MAX_FAILURES
            harness.clock.advance(Duration.ofMinutes(10))
            signIn(credentials(source = "fresh")).value()
            harness.accounts[ada.id].throttle shouldBe SignInThrottle.CLEAR
        }

        test("a success resets the consecutive failures of the account and of the source") {
            val harness = Harness()
            val ada = harness.shopper()
            val signIn = SignIn(harness.store)

            repeat(4) { signIn(credentials(password = "Wrong-$it")).error() }
            signIn(credentials()).value()
            harness.accounts[ada.id].throttle shouldBe SignInThrottle.CLEAR
            harness.sourceThrottles.throttles[SOURCE] shouldBe SignInThrottle.CLEAR
            repeat(4) { signIn(credentials(password = "Wrong-$it")).error() shouldBe IdentityError.InvalidCredentials }
            signIn(credentials()).value()
        }

        test("a source address is locked after its own limit of consecutive failures over any accounts") {
            val harness = Harness(IdentityPolicies(sourceThrottle = ThrottlePolicy(3, Duration.ofMinutes(15))))
            harness.shopper()
            val signIn = SignIn(harness.store)

            repeat(3) {
                signIn(credentials(address = "user$it@example.test")).error() shouldBe
                    IdentityError.InvalidCredentials
            }

            signIn(credentials()).error() shouldBe IdentityError.Throttled(Duration.ofMinutes(15))
            signIn(credentials(source = "elsewhere")).value()
        }

        test("an account failure lost to a concurrent change is retried, and a vanished account is left alone") {
            val harness = Harness()
            val ada = harness.shopper()
            harness.accounts.losingUpdates = 2

            SignIn(harness.store)(credentials(password = "Wrong")).error()

            harness.accounts[ada.id].throttle.failures shouldBe 1
            harness.accounts.updates shouldBe 3

            harness.accounts.losingUpdates = 3
            SignIn(harness.store)(credentials(password = "Wrong")).error()
            harness.accounts[ada.id].throttle.failures shouldBe 1

            harness.store.changeAccount(AccountId(UUID.randomUUID())) { it.succeededSignIn() }
            harness.accounts.updates shouldBe 6
        }

        test("an unverified account is refused with 403 after a correct password") {
            val harness = Harness()
            val ada = harness.shopper()
            harness.accounts.store(ada.copy(status = AccountStatus.UNVERIFIED, verifiedAt = null))

            SignIn(harness.store)(credentials()).error() shouldBe IdentityError.EmailNotVerified

            harness.sessions.sessions.values
                .shouldBeEmpty()
        }

        test("without a signing key no session is created") {
            val harness = Harness()
            harness.shopper()
            harness.signer.available = false

            SignIn(harness.store)(credentials()).error() shouldBe IdentityError.SigningUnavailable

            harness.sessions.sessions.values
                .shouldBeEmpty()
        }

        test("a refresh rotates the refresh token and issues a new access token for the same session") {
            val harness = Harness()
            val ada = harness.shopper()
            val first = SignIn(harness.store)(credentials()).value()
            harness.clock.advance(Duration.ofMinutes(20))

            val second = RefreshSession(harness.store)(first.refreshToken.value).value()

            second.refreshToken shouldBe FakeSecrets.token(1)
            second.accessToken.value shouldBe "jwt-2"
            val session = harness.sessions.of(ada.id).single()
            session.refreshTokenHash shouldBe FakeSecrets.token(1).hash()
            session.rotatedAt shouldBe NOW.plus(Duration.ofMinutes(20))
            harness.signer.grants
                .last()
                .sessionId shouldBe session.id
            harness.signer.grants
                .last()
                .issuedAt shouldBe NOW.plus(Duration.ofMinutes(20))
            RefreshSession(harness.store)(second.refreshToken.value).value().refreshToken shouldBe FakeSecrets.token(2)
        }

        test("reusing a spent refresh token revokes the session") {
            val harness = Harness()
            val ada = harness.shopper()
            val first = SignIn(harness.store)(credentials()).value()
            val second = RefreshSession(harness.store)(first.refreshToken.value).value()

            RefreshSession(harness.store)(first.refreshToken.value).error() shouldBe IdentityError.InvalidRefreshToken

            harness.sessions
                .of(ada.id)
                .single()
                .revokedAt shouldBe NOW
            RefreshSession(harness.store)(second.refreshToken.value).error() shouldBe IdentityError.InvalidRefreshToken
        }

        test("malformed, unknown, expired tokens and inactive accounts cannot refresh") {
            val harness = Harness()
            val ada = harness.shopper()
            val refresh = RefreshSession(harness.store)
            val pair = SignIn(harness.store)(credentials()).value()

            refresh("bad").error() shouldBe IdentityError.InvalidRefreshToken
            refresh(FakeSecrets.token(20).value).error() shouldBe IdentityError.InvalidRefreshToken
            harness.accounts.store(ada.copy(status = AccountStatus.UNVERIFIED))
            refresh(pair.refreshToken.value).error() shouldBe IdentityError.InvalidRefreshToken
            harness.accounts.accounts.clear()
            refresh(pair.refreshToken.value).error() shouldBe IdentityError.InvalidRefreshToken
            harness.accounts.store(ada)
            harness.clock.advance(Duration.ofDays(30))
            refresh(pair.refreshToken.value).error() shouldBe IdentityError.InvalidRefreshToken
            harness.sessions
                .of(ada.id)
                .single()
                .revokedAt
                .shouldBeNull()
        }

        test("a refresh that loses the rotation race, or finds no signing key, fails without rotating") {
            val harness = Harness()
            val ada = harness.shopper()
            val pair = SignIn(harness.store)(credentials()).value()
            harness.sessions.losingRotations = 1

            RefreshSession(harness.store)(pair.refreshToken.value).error() shouldBe IdentityError.InvalidRefreshToken
            harness.signer.available = false
            RefreshSession(harness.store)(pair.refreshToken.value).error() shouldBe IdentityError.SigningUnavailable

            harness.sessions
                .of(ada.id)
                .single()
                .refreshTokenHash shouldBe pair.refreshToken.hash()
            harness.signer.available = true
            RefreshSession(harness.store)(pair.refreshToken.value).value()
        }

        test("sign-out revokes the caller's own session only") {
            val harness = Harness()
            val ada = harness.shopper()
            SignIn(harness.store)(credentials()).value()
            val session = harness.sessions.of(ada.id).single()
            harness.clock.advance(Duration.ofMinutes(1))

            SignOut(harness.store)(AccountId(UUID.randomUUID()), session.id)
            harness.sessions
                .of(ada.id)
                .single()
                .revokedAt
                .shouldBeNull()
            SignOut(harness.store)(ada.id, null)
            SignOut(harness.store)(ada.id, SessionId(UUID.randomUUID()))
            harness.sessions
                .of(ada.id)
                .single()
                .revokedAt
                .shouldBeNull()

            SignOut(harness.store)(ada.id, session.id)
            harness.sessions
                .of(ada.id)
                .single()
                .revokedAt shouldBe NOW.plus(Duration.ofMinutes(1))
            harness.sessions.sessions.size shouldBe 1
            harness.sessions
                .of(ada.id)
                .single()
                .id
                .shouldNotBeNull()
        }
    })
