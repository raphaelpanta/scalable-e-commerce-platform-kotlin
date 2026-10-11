package com.ecommerce.build

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.gradle.testkit.runner.TaskOutcome

private const val INFRA = ":services:demo:infrastructure"
private val LAYERS = listOf("test", "integrationTest", "contractTest", "acceptanceTest")

class KotlinServicePluginTest :
    FunSpec({
        test("infrastructure has the four test layers and check depends on all of them") {
            val output = FixtureProject.prepare("layers-boundaries").run("$INFRA:check", "--dry-run").output

            LAYERS.forEach { output shouldContain "$INFRA:$it SKIPPED" }
        }

        test("one layer runs alone and executes only its own tests") {
            val fixture = FixtureProject.prepare("layers-boundaries")
            fixture.run("$INFRA:integrationTest")
            val results = fixture.file("services/demo/infrastructure/build/test-results")

            results.list()?.toList() shouldBe listOf("integrationTest")
            results.resolve("integrationTest").list()?.filter { it.endsWith(".xml") } shouldBe
                listOf("TEST-IntegrationSmokeTest.xml")
        }

        test("a failing contract test fails only the contract layer and names only that test") {
            val run =
                FixtureProject
                    .prepare("layers-failing-contract")
                    .runAndFail(*LAYERS.map { "$INFRA:$it" }.toTypedArray(), "--continue")

            run.output shouldContain "ContractSmokeTest"
            listOf("UnitSmokeTest", "IntegrationSmokeTest", "AcceptanceSmokeTest").forEach {
                run.output shouldNotContain it
            }
            run.result.task("$INFRA:contractTest")?.outcome shouldBe TaskOutcome.FAILED
            // Passed: run now, or taken from the build cache (the layers are cacheable since the build information
            // carries no build time).
            listOf("test", "integrationTest", "acceptanceTest").forEach {
                run.result.task("$INFRA:$it")?.outcome shouldBeIn listOf(TaskOutcome.SUCCESS, TaskOutcome.FROM_CACHE)
            }
        }

        test("an empty layer reports no tests and passes") {
            val fixture = FixtureProject.prepare("layers-boundaries")
            fixture.file("services/demo/infrastructure/src/acceptanceTest").deleteRecursively()

            fixture.run("$INFRA:acceptanceTest").output shouldBe ""
        }

        test("the shared architecture sources run inside the unit layer") {
            val fixture = FixtureProject.prepare("layers-boundaries")
            fixture.run("$INFRA:test")

            fixture.file("services/demo/infrastructure/build/test-results/test/TEST-MarkerTest.xml").shouldExist()
        }

        test("the style convention is inherited") {
            val output =
                FixtureProject
                    .prepare("layers-boundaries")
                    .run("$INFRA:ktlintCheck", "$INFRA:detekt", "--dry-run")
                    .output

            output shouldContain "$INFRA:ktlintCheck SKIPPED"
            output shouldContain "$INFRA:detekt SKIPPED"
        }
    
        test("a module applying kotlin-service must be named infrastructure") {
            val fixture = FixtureProject.prepare("layers-boundaries")
            fixture.file("settings.gradle.kts").appendText("include(\":services:demo:adapters\")\n")
            fixture.write("services/demo/adapters/build.gradle.kts", "plugins { id(\"kotlin-service\") }\n")

            fixture.runAndFail("help").output shouldContain
                "service module :services:demo:adapters must be named infrastructure"
        }
    })
