package com.ecommerce.catalog.application

import com.ecommerce.catalog.domain.HealthStatus
import com.ecommerce.catalog.domain.ServiceName
import com.ecommerce.catalog.domain.ServiceNameResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.pattern
import io.kotest.property.checkAll
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.yield

private val catalog: ServiceName = (ServiceName.of("catalog") as ServiceNameResult.Valid).name

private const val ITERATIONS = 100

/** A probe outcome: up, or down with a reason. */
private data class Outcome(
    val up: Boolean,
    val reason: String,
) {
    val status: HealthStatus get() = if (up) HealthStatus.Up else HealthStatus.down(reason)
}

private val outcomes =
    Arb.list(Arb.bind(Arb.boolean(), Arb.pattern("[a-z]{3,12} unreachable"), ::Outcome), 0..8)

private fun probeReturning(status: HealthStatus): HealthProbe =
    mockk {
        coEvery { check() } coAnswers {
            yield()
            status
        }
    }

class CheckServiceHealthTest :
    FunSpec({
        test("all probes up gives an up report for the service") {
            checkAll(ITERATIONS, Arb.list(Arb.boolean(), 0..8)) { flags ->
                val report = CheckServiceHealth(catalog, flags.map { probeReturning(HealthStatus.Up) })()
                report shouldBe HealthReport(catalog, HealthStatus.Up)
                report.service shouldBe catalog
            }
        }

        test("any down probe gives a down report containing every failing reason") {
            checkAll(ITERATIONS, outcomes) { results ->
                val report = CheckServiceHealth(catalog, results.map { probeReturning(it.status) })()
                val failing = results.filterNot(Outcome::up).map(Outcome::reason)
                if (failing.isEmpty()) {
                    report.status shouldBe HealthStatus.Up
                } else {
                    val down = report.status.shouldBeInstanceOf<HealthStatus.Down>()
                    failing.forEach { down.reason shouldContain it }
                    down.reason shouldBe failing.joinToString("; ")
                }
            }
        }

        test("zero probes gives an up report") {
            CheckServiceHealth(catalog, emptyList())() shouldBe HealthReport(catalog, HealthStatus.Up)
        }

        test("every probe is invoked exactly once even after a failure") {
            checkAll(ITERATIONS, outcomes) { results ->
                val probes = results.map { probeReturning(it.status) }
                CheckServiceHealth(catalog, probes)()
                probes.forEach { coVerify(exactly = 1) { it.check() } }
            }
        }

        test("a probe that fails after suspending is not swallowed: adapters map their errors to Down") {
            val failing: HealthProbe =
                mockk {
                    coEvery { check() } coAnswers {
                        yield()
                        error("probe crashed")
                    }
                }

            shouldThrow<IllegalStateException> { CheckServiceHealth(catalog, listOf(failing))() }
        }
    })
