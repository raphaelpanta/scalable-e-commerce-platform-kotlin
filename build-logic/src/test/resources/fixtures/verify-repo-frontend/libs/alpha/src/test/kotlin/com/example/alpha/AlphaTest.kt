package com.example.alpha

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class AlphaTest :
    StringSpec({
        "sum" {
            (1 + 2) shouldBe 3
        }
    })
