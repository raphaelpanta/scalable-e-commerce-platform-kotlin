package com.example.beta

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldStartWith

class BetaTest :
    StringSpec({
        "greets" {
            "beta passes" shouldStartWith "beta"
        }
    })
