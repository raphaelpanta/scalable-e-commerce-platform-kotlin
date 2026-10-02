package com.ecommerce.demo.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AgeTest :
    FunSpec({
        test("17 is not adult") { isAdult(17) shouldBe false }
        test("18 is adult") { isAdult(18) shouldBe true }
        test("19 is adult") { isAdult(19) shouldBe true }
    })
