package com.ecommerce.platform.testapp

import com.ecommerce.platform.core.problem.Problem
import com.ecommerce.platform.correlation.CorrelationIds
import com.ecommerce.platform.problem.ProblemException
import com.ecommerce.platform.problem.ProblemResponses
import com.ecommerce.platform.problem.toServerResponse
import com.ecommerce.platform.security.currentAccount
import com.ecommerce.platform.security.requireOperator
import io.opentelemetry.api.baggage.Baggage
import org.slf4j.MDC
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.coRouter
import org.springframework.web.server.ResponseStatusException

/** A minimal service exercising the platform auto-configurations in the Spring layer tests. */
@SpringBootApplication
class TestApplication {
    @Bean
    fun routes() =
        coRouter {
            GET("/api/v1/public/problem") { ProblemResponses.of(it, Problem.notFound("Nothing here.")) }
            GET("/api/v1/public/correlation") { request ->
                ServerResponse.ok().bodyValueAndAwait(
                    mapOf(
                        "current" to CorrelationIds.current(),
                        "attribute" to CorrelationIds.from(request),
                        "header" to request.headers().firstHeader(CorrelationIds.HEADER),
                        "mdc" to MDC.get(CorrelationIds.MDC_KEY),
                        "baggage" to Baggage.current().getEntryValue(CorrelationIds.BAGGAGE_KEY),
                    ),
                )
            }
            GET("/api/v1/me") {
                val account = currentAccount()
                ServerResponse.ok().bodyValueAndAwait(
                    mapOf(
                        "accountId" to account?.accountId.toString(),
                        "roles" to account?.roles?.map { role -> role.claim },
                    ),
                )
            }
            GET("/api/v1/operator") { request ->
                requireOperator().toServerResponse(
                    request,
                ) { ServerResponse.ok().bodyValueAndAwait(mapOf("ok" to true)) }
            }
            GET("/internal/ping") { ServerResponse.ok().bodyValueAndAwait(mapOf("pong" to true)) }
        }
}

@RestController
class FailingController {
    @GetMapping("/api/v1/public/boom")
    fun boom(): String = error("secret internals at db-host:5432")

    @GetMapping("/api/v1/public/conflict")
    fun conflict(): String =
        throw ProblemException(
            Problem.conflict("Already done."),
            mapOf("Retry-After" to "5"),
        )

    @GetMapping("/api/v1/public/teapot")
    fun teapot(): String = throw ResponseStatusException(HttpStatus.I_AM_A_TEAPOT, "short and stout")

    @GetMapping("/api/v1/public/gateway")
    fun gateway(): String = throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "upstream at 10.0.0.7 failed")

    @PostMapping("/api/v1/public/echo")
    fun echo(
        @RequestBody body: Map<String, Int>,
    ): Map<String, Int> = body
}
