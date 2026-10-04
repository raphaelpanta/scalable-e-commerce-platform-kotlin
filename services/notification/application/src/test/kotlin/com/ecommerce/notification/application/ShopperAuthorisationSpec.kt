package com.ecommerce.notification.application

import com.ecommerce.notification.domain.AccountId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.set
import io.kotest.property.arbitrary.uuid
import io.kotest.property.checkAll
import java.util.UUID

private val roles = Arb.set(Arb.enum<CallerRole>(), 0..CallerRole.entries.size)
private val accounts = Arb.uuid().map(::AccountId)
private val ALL = PageRequest(0, 20)

/** A world holding one notification for [account] and one for somebody else. */
private suspend fun worldWithTwoAccounts(account: AccountId): World {
    val world = World()
    world.produce(shipped(source(account)))
    world.produce(shipped(source(AccountId(UUID.randomUUID()))))
    return world
}

/**
 * Constitution III: `listOwnNotifications` authorises the caller itself (deny by default) and derives the account
 * filter from the caller, whatever the web layer checked. Any set of roles without `shopper` (an operator, or a
 * caller holding no role at all) is [Forbidden] and reads nothing.
 */
class ShopperAuthorisationSpec :
    FunSpec({
        test("a shopper lists only their own notifications") {
            checkAll(accounts) { account ->
                val world = worldWithTwoAccounts(account)
                val listed = ListOwn(world.notifications)(shopper(account), ALL).getOrNull()
                listed?.items shouldBe listOf(world.only { it.accountId == account })
                listed?.totalItems shouldBe 1L
            }
        }

        test("an operator is forbidden from the own history before anything is read") {
            val world = worldWithTwoAccounts(OPERATOR.accountId)
            val reads = world.gate.calls
            ListOwn(world.notifications)(OPERATOR, ALL).leftOrNull() shouldBe Forbidden
            world.gate.calls shouldBe reads
        }

        test("a caller holding no role is forbidden from the own history before anything is read") {
            checkAll(accounts) { account ->
                val world = worldWithTwoAccounts(account)
                val reads = world.gate.calls
                ListOwn(world.notifications)(Caller(account, emptySet()), ALL).leftOrNull() shouldBe Forbidden
                world.gate.calls shouldBe reads
            }
        }

        test("the own history lists for shoppers only, whatever other roles they hold") {
            checkAll(roles, accounts) { held, account ->
                val world = worldWithTwoAccounts(account)
                val reads = world.gate.calls
                val caller = Caller(account, held)
                val listed = ListOwn(world.notifications)(caller, ALL)
                if (CallerRole.SHOPPER in held) {
                    listed.getOrNull()?.items shouldBe listOf(world.only { it.accountId == account })
                } else {
                    listed.leftOrNull() shouldBe Forbidden
                    world.gate.calls shouldBe reads
                }
                caller.isShopper shouldBe (CallerRole.SHOPPER in held)
            }
        }
    })
