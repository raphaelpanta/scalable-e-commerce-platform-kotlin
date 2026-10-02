package com.ecommerce.notification.application

import com.ecommerce.notification.domain.BoundedContext
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DescribeServiceTest :
    FunSpec({
        test("the service describes its bounded context") {
            DescribeService()() shouldBe BoundedContext.NAME
        }
    })
