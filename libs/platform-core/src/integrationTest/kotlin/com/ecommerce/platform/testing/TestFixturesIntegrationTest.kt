package com.ecommerce.platform.testing

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.DriverManager

/** Smoke test of the shared Testcontainers fixtures: every image starts and is usable. */
class TestFixturesIntegrationTest {
    @Test
    fun `postgres starts from the pinned image`() {
        PostgresTestConfig().postgres().use { postgres ->
            postgres.start()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().executeQuery("select version()").use { result ->
                    result.next() shouldBe true
                    result.getString(1) shouldStartWith "PostgreSQL 18"
                }
            }
        }
    }

    @Test
    fun `kafka starts from the pinned image`() {
        KafkaTestConfig().kafka().use { kafka ->
            kafka.start()
            kafka.isRunning shouldBe true
            kafka.bootstrapServers shouldContain ":"
        }
    }

    @Test
    fun `mailpit receives SMTP and lists the message`() {
        MailpitContainer().use { mailpit ->
            mailpit.start()
            sendMail(mailpit.smtpHost, mailpit.smtpPort)
            val message = mailpit.messages().single()
            message.subject shouldBe "Your order"
            message.to shouldBe listOf("ada@example.com")
            mailpit.text(message.id) shouldContain "ORD-20261002-0001"
            mailpit.deleteAll()
            mailpit.messages() shouldBe emptyList()
        }
    }

    @Test
    fun `the JWT fixture serves its JWKS`() {
        JwtFixture().use { jwt ->
            val response =
                HttpClient
                    .newHttpClient()
                    .send(HttpRequest.newBuilder(URI.create(jwt.jwksUri)).build(), HttpResponse.BodyHandlers.ofString())
            response.statusCode() shouldBe HttpURLConnection.HTTP_OK
            response.body() shouldContain "\"crv\":\"Ed25519\""
            response.body() shouldContain "\"kid\":\"test-key\""
        }
    }

    private fun sendMail(
        host: String,
        port: Int,
    ) {
        Socket(host, port).use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)

            fun command(line: String) {
                writer.print(line + "\r\n")
                writer.flush()
                reader.readLine()
            }
            reader.readLine()
            command("HELO test")
            command("MAIL FROM:<shop@ecommerce.local>")
            command("RCPT TO:<ada@example.com>")
            command("DATA")
            command(
                "From: shop@ecommerce.local\r\nTo: ada@example.com\r\nSubject: Your order\r\n\r\n" +
                    "Order ORD-20261002-0001 is confirmed.\r\n.",
            )
            command("QUIT")
        }
    }
}
