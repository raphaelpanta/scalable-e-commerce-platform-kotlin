package com.ecommerce.identity.application

import com.ecommerce.identity.domain.BoundedContext
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DescribeServiceTest :
    FunSpec({
        test("the service describes its bounded context") {
            DescribeService()() shouldBe BoundedContext.NAME
        }
    })
