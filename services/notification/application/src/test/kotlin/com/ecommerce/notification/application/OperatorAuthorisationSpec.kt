package com.ecommerce.notification.application

import com.ecommerce.notification.domain.AccountId
import com.ecommerce.notification.domain.DeliveryStatus
import com.ecommerce.notification.domain.NotificationId
import com.ecommerce.notification.domain.RetryPolicy
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

/** A world holding one failed notification (one attempt, the email channel refusing). */
private suspend fun worldWithOneFailure(): World {
    val world = World(email = ScriptedChannel(SendResult.Failed(UNAVAILABLE)), policy = RetryPolicy(1))
    world.produce(shipped(source()))
    world.deliver.deliverDue(10)
    world.only().status shouldBe DeliveryStatus.FAILED
    return world
}

/**
 * Constitution III: the operator use cases authorise the caller themselves (deny by default), whatever the web layer
 * checked. Any set of roles without `operator` is [Forbidden] and reads or changes nothing.
 */
class OperatorAuthorisationSpec :
    FunSpec({
        test("the failed view lists for operators only; any other caller is forbidden before anything is read") {
            checkAll(roles, accounts) { held, account ->
                val world = worldWithOneFailure()
                val reads = world.gate.calls
                val caller = Caller(account, held)
                val listed = ListFailed(world.notifications)(caller, FailedFilter(), PageRequest(0, 20))
                if (CallerRole.OPERATOR in held) {
                    listed.getOrNull()?.items shouldBe listOf(world.only())
                } else {
                    listed.leftOrNull() shouldBe Forbidden
                    world.gate.calls shouldBe reads
                }
                caller.isOperator shouldBe (CallerRole.OPERATOR in held)
            }
        }

        test("only operators re-queue a failed notification; any other caller is forbidden and nothing changes") {
            checkAll(roles, accounts) { held, account ->
                val world = worldWithOneFailure()
                val failed = world.only()
                val reads = world.gate.calls
                val retried = world.retry(Caller(account, held), failed.id)
                if (CallerRole.OPERATOR in held) {
                    retried.getOrNull()?.status shouldBe DeliveryStatus.QUEUED
                    world.only().status shouldBe DeliveryStatus.QUEUED
                } else {
                    retried.leftOrNull() shouldBe Forbidden
                    world.only() shouldBe failed
                    world.gate.calls shouldBe reads
                }
            }
        }

        test("a forbidden caller learns nothing about unknown notifications either") {
            checkAll(accounts) { account ->
                val world = World()
                val shopper = Caller(account, setOf(CallerRole.SHOPPER))
                world.retry(shopper, NotificationId(UUID.randomUUID())).leftOrNull() shouldBe Forbidden
                world.retry(OPERATOR, NotificationId(UUID.randomUUID())).leftOrNull() shouldBe RetryRefusal.NotFound
            }
        }
    })
