package com.ecommerce.catalog.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.property.Arb
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.pattern
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

private val reasons = Arb.string(minSize = 1, maxSize = 30).filter { it.isNotBlank() }

class HealthStatusTest :
    FunSpec({
        test("a down status with a blank reason is rejected") {
            checkAll(Arb.pattern("[ \t]{0,10}")) { blank ->
                shouldThrow<IllegalArgumentException> { HealthStatus.down(blank) }
            }
        }

        test("a down status keeps any non-blank reason") {
            checkAll(reasons) { reason ->
                HealthStatus.down(reason).reason shouldBe reason
            }
        }

        test("up is a singleton object") {
            HealthStatus.Up shouldBeSameInstanceAs HealthStatus.Up
            HealthStatus.combine(emptyList()) shouldBeSameInstanceAs HealthStatus.Up
        }

        test("combining only up statuses is up") {
            checkAll(Arb.list(Arb.pattern("x"), 0..20)) { items ->
                HealthStatus.combine(items.map { HealthStatus.Up }) shouldBe HealthStatus.Up
            }
        }

        test("combining with any down status is down with every reason joined in order") {
            checkAll(Arb.list(reasons, 1..10), Arb.list(Arb.pattern("x"), 0..10)) { downReasons, ups ->
                val statuses = downReasons.map(HealthStatus::down) + ups.map { HealthStatus.Up }
                val combined = HealthStatus.combine(statuses)
                combined.shouldBeInstanceOf<HealthStatus.Down>().reason shouldBe downReasons.joinToString("; ")
            }
        }

        test("reasons keep the order of the statuses around up entries") {
            val combined =
                HealthStatus.combine(
                    listOf(HealthStatus.down("disk"), HealthStatus.Up, HealthStatus.down("database")),
                )
            combined shouldBe HealthStatus.Down("disk; database")
        }
    })
