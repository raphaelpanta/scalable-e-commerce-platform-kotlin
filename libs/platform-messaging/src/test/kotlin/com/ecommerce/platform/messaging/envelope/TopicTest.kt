package com.ecommerce.platform.messaging.envelope

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.pattern
import io.kotest.property.checkAll

class TopicTest :
    FunSpec({
        test("the six topic constants follow <context>.<aggregate>.v1") {
            Topic.ACCOUNT shouldBe Topic.name("identity", "account")
            Topic.CART shouldBe Topic.name("cart", "cart")
            Topic.STOCK shouldBe Topic.name("catalog", "stock")
            Topic.ORDER shouldBe Topic.name("order", "order")
            Topic.PAYMENT shouldBe Topic.name("payment", "payment")
            Topic.NOTIFICATION shouldBe Topic.name("notification", "notification")
            Topic.ALL shouldContainExactly
                listOf(
                    "identity.account.v1",
                    "cart.cart.v1",
                    "catalog.stock.v1",
                    "order.order.v1",
                    "payment.payment.v1",
                    "notification.notification.v1",
                )
        }

        test("partitions follow the AsyncAPI channel bindings") {
            Topic.PARTITIONS shouldBe
                mapOf(
                    Topic.ACCOUNT to 3,
                    Topic.CART to 3,
                    Topic.STOCK to 6,
                    Topic.ORDER to 6,
                    Topic.PAYMENT to 6,
                    Topic.NOTIFICATION to 3,
                )
        }

        test("every well-formed context, aggregate and version builds a valid topic") {
            checkAll(
                Arb.pattern("[a-z][a-z0-9]{0,15}"),
                Arb.pattern("[a-z][a-z0-9]{0,15}"),
                Arb.int(1..99),
            ) {
                context,
                aggregate,
                version,
                ->
                val topic = Topic.name(context, aggregate, version)
                topic shouldBe "$context.$aggregate.v$version"
                Topic.isValid(topic) shouldBe true
            }
        }

        test("malformed segments and versions are refused") {
            shouldThrow<IllegalArgumentException> { Topic.name("Order", "order") }
            shouldThrow<IllegalArgumentException> { Topic.name("order", "order.items") }
            shouldThrow<IllegalArgumentException> { Topic.name("", "order") }
            shouldThrow<IllegalArgumentException> { Topic.name("order", "order", 0) }
            Topic.isValid("order.order") shouldBe false
            Topic.isValid("order.order.v0") shouldBe false
            Topic.isValid("order.order.v1.dlt") shouldBe false
        }

        test("the dead-letter topic appends .dlt to a valid topic only") {
            Topic.deadLetter(Topic.ORDER) shouldBe "order.order.v1.dlt"
            shouldThrow<IllegalArgumentException> { Topic.deadLetter("order.order.v1.dlt") }
        }
    })
