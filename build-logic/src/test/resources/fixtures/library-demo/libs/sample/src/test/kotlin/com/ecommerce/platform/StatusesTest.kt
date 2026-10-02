package com.ecommerce.platform

import com.ecommerce.platform.testing.Codes
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpStatus

class StatusesTest :
    StringSpec({
        "a code maps to its Spring status" {
            statusOf(Codes.NOT_FOUND) shouldBe HttpStatus.NOT_FOUND
        }

        "only 400 to 499 are client errors" {
            isClientError(Codes.LAST_REDIRECT) shouldBe false
            isClientError(Codes.FIRST_CLIENT_ERROR) shouldBe true
            isClientError(Codes.LAST_CLIENT_ERROR) shouldBe true
            isClientError(Codes.FIRST_SERVER_ERROR) shouldBe false
        }
    })
