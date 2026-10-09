package com.ecommerce.build

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private const val GIB = 1L shl 30

class HeavyTaskLimitTest :
    FunSpec({
        test("the CI runner's 10 GB cap gets three heavy tasks at once") {
            heavyTaskSlots(10 * GIB) shouldBe 3
        }

        test("a 16 GB laptop gets more slots than its four Gradle workers, so nothing changes there") {
            heavyTaskSlots(16 * GIB) shouldBe 7
        }

        test("small machines keep two slots, so tests and Pitest of two modules still overlap") {
            listOf(4L, 6L, 8L).forEach { heavyTaskSlots(it * GIB) shouldBe 2 }
        }

        test("slots grow with the memory: three per 4 GiB beyond the 6 GiB the daemons and Node take") {
            heavyTaskSlots(14 * GIB) shouldBe 6
            heavyTaskSlots(30 * GIB) shouldBe 18
        }
    })
