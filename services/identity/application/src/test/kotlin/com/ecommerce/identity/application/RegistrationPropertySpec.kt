package com.ecommerce.identity.application

import com.ecommerce.identity.application.IdentityArbs.PROPERTIES
import com.ecommerce.identity.domain.AccountStatus
import com.ecommerce.identity.domain.IdentityError
import com.ecommerce.identity.domain.TokenPurpose
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeUnique
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import java.time.Duration

private const val MAX_REPEATS = 5
private const val MAX_GAP_SECONDS = 3600L

/**
 * T122, T134 / FR-004: registering an email again while its account is unverified re-sends a fresh verification token
 * (only the newest one verifies) without touching the password; once verified, nothing is sent. Every answer is the
 * same success.
 */
class RegistrationPropertySpec :
    FunSpec({
        test("each registration of a still unverified email sends a fresh token, and only the newest verifies") {
            checkAll(
                PROPERTIES,
                IdentityArbs.email,
                IdentityArbs.password,
                Arb.int(1..MAX_REPEATS),
                IdentityArbs.duration(MAX_GAP_SECONDS),
            ) { address, password, repeats, gap ->
                val harness = Harness()
                val register = RegisterAccount(harness.store)

                repeat(repeats) { attempt ->
                    val typed = if (attempt % 2 == 0) address else address.uppercase()
                    register(Registration(typed, "$password$attempt", null)).value()
                    harness.clock.advance(gap)
                }

                val account =
                    harness.accounts.accounts.values
                        .single()
                account.passwordHash?.value shouldBe "hash:${password}0"
                account.status shouldBe AccountStatus.UNVERIFIED
                val events = harness.events.registered
                events shouldHaveSize repeats
                events.map { it.verificationToken.value }.shouldBeUnique()
                events.forEach { it.recipient.accountId shouldBe account.id }
                events.forEachIndexed { index, event ->
                    event.tokenExpiresAt shouldBe NOW.plus(gap.multipliedBy(index.toLong())).plus(Duration.ofHours(24))
                }
                harness.tokens.of(account.id, TokenPurpose.EMAIL_VERIFICATION).count { it.usedAt == null } shouldBe 1
                harness.hasher.hashed shouldBe repeats

                events.dropLast(1).forEach {
                    VerifyEmail(harness.store)(it.verificationToken.value).error() shouldBe IdentityError.InvalidToken
                }
                VerifyEmail(harness.store)(events.last().verificationToken.value).value()
                register(Registration(address, password, null)).value()
                harness.events.registered shouldHaveSize repeats
                harness.accounts[account.id].status shouldBe AccountStatus.ACTIVE
            }
        }
    })
