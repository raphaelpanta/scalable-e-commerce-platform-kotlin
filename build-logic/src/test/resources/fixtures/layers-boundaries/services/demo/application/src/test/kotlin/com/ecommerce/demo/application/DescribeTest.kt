package com.ecommerce.demo.application

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class DescribeTest :
    StringSpec({
        "a blank value is described as blank" {
            describe(" ") shouldBe "blank"
        }
        "a named value is described by itself" {
            describe("a") shouldBe "a"
        }
    })
