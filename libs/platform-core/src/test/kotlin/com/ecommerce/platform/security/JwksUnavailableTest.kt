package com.ecommerce.platform.security

import com.ecommerce.platform.core.problem.ProblemType
import com.ecommerce.platform.testapp.TestApplication
import com.ecommerce.platform.testing.JwtFixture
import com.ecommerce.platform.testing.ProblemAssertions.expectProblem
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.web.reactive.server.WebTestClient
import java.util.UUID

/** Without any reachable JWKS a token is neither accepted nor blamed: the answer is 503 `unavailable`. */
@SpringBootTest(
    classes = [TestApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "management.server.port=",
        "platform.security.jwks-uri=http://127.0.0.1:1/.well-known/jwks.json",
    ],
)
class JwksUnavailableTest(
    @LocalServerPort private val port: Int,
) {
    @Test
    fun `an unreachable JWKS answers 503`() {
        WebTestClient
            .bindToServer()
            .baseUrl("http://localhost:$port")
            .build()
            .get()
            .uri("/api/v1/me")
            .headers { it.setBearerAuth(JwtFixture().tokenFor(UUID.randomUUID())) }
            .exchange()
            .expectProblem(ProblemType.UNAVAILABLE)
    }
}
