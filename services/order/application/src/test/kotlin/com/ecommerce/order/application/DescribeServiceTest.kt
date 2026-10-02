package com.ecommerce.order.application

import com.ecommerce.order.domain.BoundedContext
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DescribeServiceTest :
    FunSpec({
        test("the service describes its bounded context") {
            DescribeService()() shouldBe BoundedContext.NAME
        }
    })
