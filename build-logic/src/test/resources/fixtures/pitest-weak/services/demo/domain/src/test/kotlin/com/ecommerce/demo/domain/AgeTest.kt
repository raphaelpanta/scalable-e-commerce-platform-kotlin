package com.ecommerce.demo.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AgeTest :
    FunSpec({
        test("30 is adult") { isAdult(30) shouldBe true }
    })
