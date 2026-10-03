package com.ecommerce.build

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import org.gradle.testkit.runner.TaskOutcome

private const val INFRA = ":services:demo:infrastructure"
private const val CONSUMER = ":services:gateway:contractTest"
private const val VERIFY = ":services:echo:contractVerify"
private const val ECHO_RESULTS = "services/echo/build/test-results"

/** Prints the system properties and JVM arguments the contract tasks of [project] pass to the test JVM. */
private fun FixtureProject.printContractProperties(project: String) {
    file("$project/build.gradle.kts").appendText(
        """

        tasks.register("printContractProperties") {
            val properties =
                listOf("contractTest", "contractVerify").map { name ->
                    tasks.named<Test>(name).map { task ->
                        task.systemProperties.map { (key, value) -> "${'$'}name ${'$'}key=${'$'}value" } +
                            task.allJvmArgs.filter { it.startsWith("-Dpact") }.map { "${'$'}name ${'$'}it" }
                    }
                }
            doLast { properties.flatMap { it.get() }.sorted().forEach(::println) }
        }
        """.trimIndent(),
    )
}

/** The `<testcase name="...">` values of the contractVerify reports of the echo provider module. */
private fun FixtureProject.verifiedCases(): List<String> =
    file("$ECHO_RESULTS/contractVerify")
        .listFiles { file -> file.extension == "xml" }
        .orEmpty()
        .flatMap { report ->
            Regex("""<testcase name="([^"]+)"""").findAll(report.readText()).map { it.groupValues[1] }.toList()
        }

class PactPluginTest :
    FunSpec({
        test("the contract layer carries Pact consumer and provider support") {
            val output =
                FixtureProject
                    .prepare("layers-boundaries")
                    .run("$INFRA:dependencies", "--configuration", "contractTestRuntimeClasspath")
                    .output

            output shouldContain "au.com.dius.pact.consumer:junit5"
            output shouldContain "au.com.dius.pact.provider:junit5"
        }

        test("both contract tasks read and write pacts in the repository root build/pacts") {
            val fixture = FixtureProject.prepare("layers-boundaries")
            fixture.printContractProperties("services/demo/infrastructure")
            val lines = fixture.run("$INFRA:printContractProperties").output.lines()
            val pacts = fixture.dir.resolve("build/pacts").canonicalPath

            listOf("contractTest", "contractVerify").forEach { task ->
                lines shouldContain "$task pact.rootDir=$pacts"
                lines shouldContain "$task pact.folder=$pacts"
                lines shouldContain "$task pact.writer.overwrite=true"
                lines shouldContain "$task -Dpact.verifier.publishResults=false"
            }
            lines.none { it.contains("pactbroker") } shouldBe true
        }

        test("check runs contractVerify, which is ordered after every contractTest of the build") {
            val output = FixtureProject.prepare("boot-app-gateway").run("check", "--dry-run").output.lines()

            output shouldContain "$VERIFY SKIPPED"
            output.indexOf("$CONSUMER SKIPPED") shouldBeLessThan output.indexOf("$VERIFY SKIPPED")
        }

        test("a consumer pact of one module is written to build/pacts and verified by the provider module") {
            val fixture = FixtureProject.prepare("boot-app-gateway")

            // Requested in reverse order: contractVerify still waits for the consumer of the other module.
            val run = fixture.run(VERIFY, CONSUMER)

            run.output shouldBe ""
            val order = run.result.tasks.map { it.path }
            order.indexOf(CONSUMER) shouldBeLessThan order.indexOf(VERIFY)
            fixture.file("build/pacts/gateway-echo.json").shouldExist()
            fixture.verifiedCases() shouldContain "gateway - an echo request"
            // The provider with no pact yet stays green thanks to @IgnoreNoPactsToVerify.
            fixture.verifiedCases() shouldContain "No pacts found to verify"
            // Consumer tests stay out of contractVerify and provider classes out of contractTest.
            fixture.file("$ECHO_RESULTS/contractTest").exists() shouldBe false
            fixture.file("services/gateway/build/test-results/contractTest")
                .list()
                .orEmpty()
                .toList() shouldContain "TEST-com.ecommerce.gateway.EchoConsumerPactTest.xml"
        }

        test("a provider that breaks a consumer pact fails contractVerify") {
            val fixture = FixtureProject.prepare("boot-app-gateway")
            val provider = fixture.file("services/echo/src/contractTest/kotlin/com/ecommerce/echo")
            provider.resolve("EchoProviderVerificationTest.kt").apply {
                writeText(readText().replace("""{"message":"hello"}""", """{"note":"hello"}"""))
            }

            val run = fixture.runAndFail(VERIFY, CONSUMER)

            run.result.task(CONSUMER)?.outcome shouldBe TaskOutcome.SUCCESS
            run.result.task(VERIFY)?.outcome shouldBe TaskOutcome.FAILED
            run.output shouldContain "EchoProviderVerificationTest"
            run.output shouldNotContain "LonelyProviderVerificationTest"
        }

        test("a pact broker in the environment adds the broker, version and publishing properties") {
            val fixture = FixtureProject.prepare("boot-app-gateway")
            fixture.printContractProperties("services/echo")
            val lines =
                fixture
                    .runner(":services:echo:printContractProperties")
                    .withEnvironment(
                        System.getenv() +
                            mapOf(
                                "PACT_BROKER_URL" to "https://broker.example",
                                "PACT_BROKER_TOKEN" to "secret-token",
                                "GITHUB_SHA" to "0123abc",
                                "PACT_PUBLISH_RESULTS" to "true",
                                "PACT_PROVIDER_BRANCH" to "main",
                                "PACT_URL" to "https://broker.example/pacts/provider/echo/consumer/gateway/latest",
                                "PACT_CONSUMER" to "gateway",
                            ),
                    ).build()
                    .output
                    .lines()

            listOf(
                "-Dpactbroker.url=https://broker.example",
                "-Dpactbroker.auth.token=secret-token",
                "-Dpact.provider.version=0123abc",
                "-Dpact.verifier.publishResults=true",
                "-Dpact.provider.branch=main",
                "-Dpactbroker.providerBranch=main",
                "-Dpact.filter.pacturl=https://broker.example/pacts/provider/echo/consumer/gateway/latest",
                "-Dpact.filter.consumers=gateway",
            ).forEach { lines shouldContain "contractVerify $it" }
            lines.filter { it.contains("pactbroker.auth.username") } shouldBe emptyList()
            lines shouldNotContain "contractVerify -Dpact.verifier.publishResults=false"
            lines.single { it.startsWith("contractVerify pact.folder=") } shouldEndWith "build/pacts"
        }
    })
