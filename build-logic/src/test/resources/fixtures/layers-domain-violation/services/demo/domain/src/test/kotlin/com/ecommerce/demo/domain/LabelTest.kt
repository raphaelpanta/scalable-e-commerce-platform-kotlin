package com.ecommerce.demo.domain

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class LabelTest :
    StringSpec({
        "a blank label is blank" {
            isBlankLabel(" ") shouldBe true
        }
        "a named label is not blank" {
            isBlankLabel("a") shouldBe false
        }
    })
