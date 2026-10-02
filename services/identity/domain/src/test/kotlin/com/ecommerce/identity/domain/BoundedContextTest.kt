package com.ecommerce.identity.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BoundedContextTest :
    FunSpec({
        test("the bounded context is named after the service") {
            BoundedContext.NAME shouldBe "identity"
        }
    })
