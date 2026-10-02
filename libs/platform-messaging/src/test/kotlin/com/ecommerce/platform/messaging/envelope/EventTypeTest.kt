package com.ecommerce.platform.messaging.envelope

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class EventTypeTest :
    FunSpec({
        test("the registry holds the 21 event types of events.yaml") {
            EventType.entries shouldHaveSize 21
        }

        test("every type resolves to its own topic and its producer is the topic's context") {
            EventType.entries.forEach { type ->
                EventType.find(type.name) shouldBe type
                EventType.topicOf(type.name) shouldBe type.topic
                type.topic.startsWith("${type.producer}.") shouldBe true
                Topic.ALL.contains(type.topic) shouldBe true
            }
        }

        test("types are grouped per channel as in the contract") {
            EventType.publishedTo(Topic.ACCOUNT) shouldContainExactly
                listOf(
                    EventType.AccountRegistered,
                    EventType.AccountVerified,
                    EventType.AccountDeleted,
                    EventType.PasswordResetRequested,
                )
            EventType.publishedTo(Topic.CART) shouldContainExactly listOf(EventType.CartMerged)
            EventType.publishedTo(Topic.STOCK) shouldHaveSize 3
            EventType.publishedTo(Topic.ORDER) shouldHaveSize 7
            EventType.publishedTo(Topic.PAYMENT) shouldHaveSize 4
            EventType.publishedTo(Topic.NOTIFICATION) shouldHaveSize 2
            EventType.OrderPaid.producer shouldBe "order"
            EventType.StockCommitted.producer shouldBe "catalog"
        }

        test("an unknown type is tolerated on read but cannot be published") {
            EventType.find("OrderTeleported").shouldBeNull()
            shouldThrow<IllegalArgumentException> { EventType.topicOf("OrderTeleported") }.message shouldContain
                "OrderTeleported"
        }
    })
