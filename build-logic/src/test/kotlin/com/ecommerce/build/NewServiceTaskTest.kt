package com.ecommerce.build

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.file.shouldNotExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

private const val MARKER = "// --- includeService registry (the newService task appends below this line) ---"
private const val TEMPLATE = "build-logic/src/main/resources/service-template"

private val repoRoot: File = File(requireNotNull(System.getProperty("repo.root")))

/** The scaffold fixture with the repository's real architecture rules, so `verify` checks the new service. */
private fun scaffoldRepo(): FixtureProject =
    FixtureProject.prepare("scaffold-repo").apply {
        repoRoot.resolve("config/architecture").copyRecursively(file("config/architecture"))
    }

class NewServiceTaskTest :
    FunSpec({
        test("newService creates three one-line modules and registers the service below the marker") {
            val fixture = scaffoldRepo()
            val settingsBefore = fixture.file("settings.gradle.kts").readLines()

            fixture.run("newService", "-Pname=orders")

            val conventions =
                mapOf(
                    "domain" to "kotlin-domain",
                    "application" to "kotlin-application",
                    "infrastructure" to "kotlin-service",
                )
            conventions.forEach { (module, convention) ->
                fixture.file("services/orders/$module/build.gradle.kts").readLines() shouldBe
                    listOf("plugins { id(\"$convention\") }")
            }
            val settingsAfter = fixture.file("settings.gradle.kts").readLines()
            val registration = settingsBefore.indexOf("includeService(\"demo\")") + 1
            settingsAfter shouldBe
                settingsBefore.take(registration) + "includeService(\"orders\")" + settingsBefore.drop(registration)
            settingsAfter.indexOf("includeService(\"orders\")") shouldBe settingsAfter.indexOf(MARKER) + 2
        }

        test("a generated service passes verify with no further edits (SC-004)") {
            val fixture = scaffoldRepo()
            fixture.run("newService", "-Pname=orders")

            fixture.file("services/orders/infrastructure/src/main/kotlin/com/ecommerce/orders/infrastructure")
                .resolve("OrdersApplication.kt")
                .readText() shouldContain "class OrdersApplication"
            fixture.run("verify").output.lines().count { it.isNotBlank() } shouldBe 0
        }

        test("an invalid name is refused and nothing is written") {
            listOf("Orders", "order-items", "1st", "").forEach { invalid ->
                val fixture = scaffoldRepo()
                val settings = fixture.file("settings.gradle.kts").readText()

                fixture.runAndFail("newService", "-Pname=$invalid").output shouldContain
                    "name must match [a-z][a-z0-9]*"
                fixture.file("settings.gradle.kts").readText() shouldBe settings
                fixture.file("services").list()?.toList() shouldBe listOf("demo")
            }
        }

        test("an existing service is refused, also on a second run") {
            val fixture = scaffoldRepo()
            fixture.runAndFail("newService", "-Pname=demo").output shouldContain "already exists"

            fixture.run("newService", "-Pname=orders")
            val settings = fixture.file("settings.gradle.kts").readText()
            fixture.runAndFail("newService", "-Pname=orders").output shouldContain "already exists"
            fixture.file("settings.gradle.kts").readText() shouldBe settings
        }

        test("running without a name explains the usage") {
            val fixture = scaffoldRepo()

            fixture.runAndFail("newService").output shouldContain "-Pname=<context> is required"
            fixture.file("services/orders").shouldNotExist()
            fixture.run("help", "--task", "newService").output shouldContain
                "usage: ./gradlew newService -Pname=<context>"
        }

        test("the template index lists exactly the template files") {
            val root = repoRoot.resolve(TEMPLATE)
            val onDisk =
                root
                    .walkTopDown()
                    .filter { it.isFile && it.name != "index.txt" }
                    .map { it.relativeTo(root).invariantSeparatorsPath }
                    .sorted()
                    .toList()

            root.resolve("index.txt").readLines().filter { it.isNotBlank() }.sorted() shouldBe onDisk
        }
    })
