package com.ecommerce.gateway

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class GatewayApplicationTest :
    StringSpec({
        "the application class is named after the gateway" {
            GatewayApplication::class.simpleName shouldBe "GatewayApplication"
        }
    })
