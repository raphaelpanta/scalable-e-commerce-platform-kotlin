package com.ecommerce.notification.infrastructure

import com.ecommerce.platform.testing.JwtFixture
import com.ecommerce.platform.testing.MailpitContainer
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistrar
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

private const val SMTP_REFUSED = 451
private const val ALWAYS = 100
private const val HTTP_OK = 200
private const val HTTP_LAST_SUCCESS = 299

/**
 * The outside world of the integration layer, all bean-managed so that it stops with the context: Mailpit (chaos
 * enabled) as SMTP server, a WireMock stand-in for identity's contact endpoint and the JWKS of [JwtFixture].
 */
@TestConfiguration(proxyBeanMethods = false)
class DeliveryTestConfig {
    @Bean
    fun mailpit(): MailpitContainer = MailpitContainer().withEnv("MP_ENABLE_CHAOS", "true")

    @Bean(destroyMethod = "stop")
    fun identity(): WireMockServer = WireMockServer(options().dynamicPort()).apply { start() }

    @Bean(destroyMethod = "close")
    fun jwt(): JwtFixture = JwtFixture().also { it.startJwks() }

    @Bean
    fun outsideWorld(
        mailpit: MailpitContainer,
        identity: WireMockServer,
        jwt: JwtFixture,
    ): DynamicPropertyRegistrar =
        DynamicPropertyRegistrar { registry ->
            registry.add("spring.mail.host") { mailpit.smtpHost }
            registry.add("spring.mail.port") { mailpit.smtpPort }
            registry.add("notification.identity-url") { identity.baseUrl() }
            registry.add("platform.security.jwks-uri") { jwt.jwksUri }
        }
}

/** Contact answers of the identity stand-in (identity-internal.yaml `AccountContact`). */
object IdentityStub {
    fun emailOnly(
        identity: WireMockServer,
        accountId: UUID,
        email: String,
    ) = contact(
        identity,
        accountId,
        """"email":"$email","phoneVerified":false,"channels":["email"],"anonymised":false""",
    )

    fun smsOptedIn(
        identity: WireMockServer,
        accountId: UUID,
        email: String,
        phone: String,
    ) = contact(
        identity,
        accountId,
        """"email":"$email","phoneNumber":"$phone","phoneVerified":true,""" +
            """"channels":["email","sms"],"anonymised":false""",
    )

    fun anonymised(
        identity: WireMockServer,
        accountId: UUID,
    ) = contact(
        identity,
        accountId,
        """"email":"anon-4f9c2d71@anonymised.invalid","phoneVerified":false,"channels":[],"anonymised":true""",
    )

    private fun contact(
        identity: WireMockServer,
        accountId: UUID,
        members: String,
    ) {
        identity.stubFor(
            get(urlEqualTo("/internal/accounts/$accountId/contact")).willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("""{"accountId":"$accountId",$members}"""),
            ),
        )
    }
}

/** Mailpit's chaos API (`PUT /api/v1/chaos`), the way the acceptance suite makes the email channel fail. */
object MailpitChaos {
    private val http: HttpClient = HttpClient.newHttpClient()

    fun refuseEveryRecipient(mailpit: MailpitContainer) = put(mailpit, ALWAYS)

    fun acceptEveryRecipient(mailpit: MailpitContainer) = put(mailpit, 0)

    private fun put(
        mailpit: MailpitContainer,
        probability: Int,
    ) {
        val body = """{"Recipient":{"ErrorCode":$SMTP_REFUSED,"Probability":$probability}}"""
        val request =
            HttpRequest
                .newBuilder(URI.create("${mailpit.apiUrl}/api/v1/chaos"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(
            response.statusCode() in HTTP_OK..HTTP_LAST_SUCCESS,
        ) { "Mailpit chaos refused: HTTP ${response.statusCode()}" }
    }
}
