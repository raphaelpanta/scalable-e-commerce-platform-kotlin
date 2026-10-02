package com.ecommerce.platform

import com.ecommerce.platform.testing.Codes
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class StatusesIntegrationTest :
    StringSpec({
        "the integration layer sees the main classes and the test fixtures" {
            isClientError(Codes.NOT_FOUND) shouldBe true
        }
    })
