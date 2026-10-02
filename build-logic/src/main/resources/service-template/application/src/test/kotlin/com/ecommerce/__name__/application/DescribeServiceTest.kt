package com.ecommerce.__name__.application

import com.ecommerce.__name__.domain.BoundedContext
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DescribeServiceTest :
    FunSpec({
        test("the service describes its bounded context") {
            DescribeService()() shouldBe BoundedContext.NAME
        }
    })
