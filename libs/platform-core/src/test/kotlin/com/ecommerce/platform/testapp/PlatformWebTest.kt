package com.ecommerce.platform.testapp

import com.ecommerce.platform.testing.InternalToken
import com.ecommerce.platform.testing.JwtFixture
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration

/**
 * Base of the Spring layer tests (subclasses pass the `@LocalServerPort`): the [TestApplication] on a random port
 * (management on the same port), a JWKS served by [JwtFixture], the test internal token and an allowed CORS origin.
 */
@SpringBootTest(
    classes = [TestApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "management.server.port=",
        "platform.security.public-paths=/api/v1/public/**",
        "platform.security.internal-token=" + InternalToken.TEST,
        "platform.security.cors.allowed-origins=" + PlatformWebTest.ALLOWED_ORIGIN,
    ],
)
open class PlatformWebTest(
    private val port: Int,
) {
    internal val client: WebTestClient by lazy {
        WebTestClient
            .bindToServer()
            .baseUrl("http://localhost:$port")
            .responseTimeout(Duration.ofSeconds(10))
            .build()
    }

    companion object {
        const val ALLOWED_ORIGIN: String = "https://shop.example"
        val jwt: JwtFixture = JwtFixture()

        @JvmStatic
        @DynamicPropertySource
        fun jwks(registry: DynamicPropertyRegistry) {
            registry.add("platform.security.jwks-uri") { jwt.jwksUri }
        }
    }
}
