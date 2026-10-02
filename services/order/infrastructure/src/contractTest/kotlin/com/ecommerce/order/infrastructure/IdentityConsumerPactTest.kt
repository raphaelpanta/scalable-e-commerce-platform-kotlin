package com.ecommerce.order.infrastructure

import arrow.core.right
import au.com.dius.pact.consumer.MessagePactBuilder
import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.consumer.junit5.ProviderType
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.V4Pact
import au.com.dius.pact.core.model.annotations.Pact
import com.ecommerce.order.application.AnonymiseAccountOrders
import com.ecommerce.order.domain.AccountId
import com.ecommerce.order.domain.AddressId
import com.ecommerce.order.domain.Channel
import com.ecommerce.order.domain.DeliveryAddress
import com.ecommerce.order.domain.Recipient
import com.ecommerce.order.infrastructure.PactValues.ADA
import com.ecommerce.order.infrastructure.PactValues.ADDRESS_FOREIGN
import com.ecommerce.order.infrastructure.PactValues.ADDRESS_OWNED
import com.ecommerce.order.infrastructure.PactValues.ADDRESS_UNKNOWN
import com.ecommerce.order.infrastructure.PactValues.OTHER_ACCOUNT
import com.ecommerce.order.infrastructure.clients.AddressClient
import com.ecommerce.order.infrastructure.messaging.Handling
import com.ecommerce.order.infrastructure.messaging.OrderEventHandlers
import com.ecommerce.platform.messaging.envelope.Topic
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID

private const val PSEUDONYM = "anon-4f9c2d71"

private fun addressState(
    accountId: String,
    addressId: String,
): Map<String, Any> =
    mapOf(
        "recipientName" to "Ada Lovelace",
        "line1" to "12 Analytical Street",
        "line2" to "Flat 2",
        "city" to "London",
        "region" to "England",
        "postalCode" to "N1 9GU",
        "countryCode" to "GB",
        "accountId" to accountId,
        "addressId" to addressId,
    )

private fun addressPath(addressId: String) = "/internal/accounts/$ADA/addresses/$addressId"

/**
 * Consumer side of order -> identity: the delivery address (pact-interactions.md section 2.5) through the real
 * [AddressClient], the contact snapshot the order events carry (the `getAccountContact` interaction of section 2.6,
 * also used by order), and the `AccountDeleted` event (section 3.2) through the real [OrderEventHandlers].
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = "identity", pactVersion = PactSpecVersion.V4)
@Suppress("TooManyFunctions") // one pact method and one test per interaction of the tables
class IdentityConsumerPactTest {
    private val anonymise = mockk<AnonymiseAccountOrders>()
    private val handlers = OrderEventHandlers(mockk(), mockk(), anonymise)

    @Pact(consumer = "order")
    fun ownedAddress(builder: PactDslWithProvider): V4Pact =
        builder
            .given("an address exists", addressState(ADA, ADDRESS_OWNED))
            .internalRequest("a request for an address owned by the account", "GET", addressPath(ADDRESS_OWNED))
            .jsonAnswer(
                PactValues.OK,
                json { body ->
                    body.stringValue("recipientName", "Ada Lovelace")
                    body.stringValue("line1", "12 Analytical Street")
                    body.stringValue("line2", "Flat 2")
                    body.stringValue("city", "London")
                    body.stringValue("region", "England")
                    body.stringValue("postalCode", "N1 9GU")
                    body.stringValue("countryCode", "GB")
                },
            ).toPact(V4Pact::class.java)

    @Pact(consumer = "order")
    fun foreignAddress(builder: PactDslWithProvider): V4Pact =
        builder
            .given("an address exists", addressState(OTHER_ACCOUNT, ADDRESS_FOREIGN))
            .internalRequest("a request for an address owned by another account", "GET", addressPath(ADDRESS_FOREIGN))
            .problemAnswer(PactValues.NOT_FOUND, "not-found", "Not found", "Address not found.")
            .toPact(V4Pact::class.java)

    @Pact(consumer = "order")
    fun unknownAddress(builder: PactDslWithProvider): V4Pact =
        builder
            .given("no address exists", mapOf("accountId" to ADA, "addressId" to ADDRESS_UNKNOWN))
            .internalRequest("a request for an address that does not exist", "GET", addressPath(ADDRESS_UNKNOWN))
            .problemAnswer(PactValues.NOT_FOUND, "not-found", "Not found", "Address not found.")
            .toPact(V4Pact::class.java)

    @Pact(consumer = "order")
    fun contact(builder: PactDslWithProvider): V4Pact =
        builder
            .given(
                "an account exists",
                mapOf(
                    "accountId" to ADA,
                    "email" to "ada@example.test",
                    "phoneVerified" to false,
                    "channels" to listOf("email"),
                ),
            ).internalRequest(
                "a request for the contact details of an account with email only",
                "GET",
                "/internal/accounts/$ADA/contact",
            ).jsonAnswer(
                PactValues.OK,
                json { body ->
                    body.stringValue("accountId", ADA)
                    body.stringValue("email", "ada@example.test")
                    body.booleanValue("phoneVerified", false)
                    body.array("channels") { it.stringValue("email") }
                    body.booleanValue("anonymised", false)
                },
            ).toPact(V4Pact::class.java)

    @Pact(consumer = "order")
    fun accountDeleted(builder: MessagePactBuilder): V4Pact =
        builder
            .given("an account was deleted", mapOf("accountId" to ADA, "pseudonym" to PSEUDONYM))
            .expectsToReceive("an AccountDeleted event for the owner of open orders")
            .withMetadata(mapOf("topic" to Topic.ACCOUNT, "kafkaKey" to ADA))
            .withContent(
                envelope(
                    "ee000004-0000-4000-8000-000000000004",
                    "AccountDeleted",
                    "2026-10-02T12:00:00Z",
                    ADA,
                    "identity",
                ) {
                    it.stringValue("accountId", ADA)
                    it.stringValue("pseudonym", PSEUDONYM)
                },
            ).toPact(V4Pact::class.java)

    @Test
    @PactTestFor(pactMethod = "ownedAddress")
    fun `an owned address is the delivery address snapshot`(mockServer: MockServer) {
        address(mockServer, ADDRESS_OWNED) shouldBe
            DeliveryAddress("Ada Lovelace", "12 Analytical Street", "Flat 2", "London", "England", "N1 9GU", "GB")
    }

    @Test
    @PactTestFor(pactMethod = "foreignAddress")
    fun `another account's address is not found`(mockServer: MockServer) {
        address(mockServer, ADDRESS_FOREIGN) shouldBe null
    }

    @Test
    @PactTestFor(pactMethod = "unknownAddress")
    fun `an unknown address is not found`(mockServer: MockServer) {
        address(mockServer, ADDRESS_UNKNOWN) shouldBe null
    }

    @Test
    @PactTestFor(pactMethod = "contact")
    fun `the contact details are the recipient snapshot`(mockServer: MockServer) {
        withPactCorrelation { AddressClient(internalClient(mockServer)).recipient(ACCOUNT) } shouldBe
            Recipient("ada@example.test", null, listOf(Channel.EMAIL))
    }

    @Test
    @PactTestFor(pactMethod = "accountDeleted", providerType = ProviderType.ASYNCH)
    fun `AccountDeleted anonymises the account's orders`(pact: V4Pact) {
        coEvery { anonymise(any(), any()) } returns 2.right()
        withPactCorrelation { handlers.onAccountEvent(receivedMessage(pact)) } shouldBe Handling.APPLIED
        coVerify { anonymise(ACCOUNT, PSEUDONYM) }
    }

    private fun address(
        mockServer: MockServer,
        addressId: String,
    ): DeliveryAddress? =
        withPactCorrelation {
            AddressClient(internalClient(mockServer)).address(ACCOUNT, AddressId(UUID.fromString(addressId)))
        }

    private companion object {
        val ACCOUNT = AccountId(UUID.fromString(ADA))
    }
}
