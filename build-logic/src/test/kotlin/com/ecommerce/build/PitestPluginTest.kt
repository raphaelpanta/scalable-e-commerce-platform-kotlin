package com.ecommerce.build

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.gradle.testkit.runner.TaskOutcome

private const val PITEST = ":services:demo:domain:pitest"
private const val LIBRARY_PITEST = ":libs:sample:pitest"
private const val MUTATIONS = "libs/sample/build/reports/pitest/mutations.xml"

/** The library fixture with `pitest` applied and [settings] appended to its build script. */
private fun libraryWithPitest(settings: String = ""): FixtureProject =
    FixtureProject.prepare("library-demo").apply {
        file("libs/sample/build.gradle.kts").writeText(
            "plugins {\n    id(\"kotlin-library\")\n    id(\"pitest\")\n}\n\n" +
                "dependencies {\n    implementation(libs.spring.boot.starter.webflux)\n}\n" + settings,
        )
    }

/** The distinct classes Pitest mutated, from its XML report. */
private fun FixtureProject.mutatedClasses(): Set<String> =
    Regex("<mutatedClass>([^<]+)</mutatedClass>")
        .findAll(file(MUTATIONS).takeIf { it.isFile }?.readText().orEmpty())
        .map { it.groupValues[1] }
        .toSet()

class PitestPluginTest :
    FunSpec({
        test("strong tests pass the default threshold of 80") {
            FixtureProject.prepare("pitest-strong").run(PITEST).output shouldBe ""
        }

        test("weak tests fail and report the mutation score against 80") {
            val output = FixtureProject.prepare("pitest-weak").runAndFail(PITEST).output

            output shouldContain "Mutation score"
            output shouldContain "80"
        }

        test("a module without tests skips pitest") {
            FixtureProject.prepare("pitest-no-tests").run(PITEST).result.task(PITEST)?.outcome shouldBe
                TaskOutcome.SKIPPED
        }

        test("a threshold below the floor fails at configuration") {
            FixtureProject.prepare("pitest-low-threshold").runAndFail("help").output shouldContain
                "below the constitution minimum 80"
        }

        test("harness.mutation.classes narrows the mutated classes (incremental run of the Stop hook)") {
            // The weak fixture fails on its own; with a glob that matches none of its classes nothing is mutated.
            FixtureProject
                .prepare("pitest-weak")
                .run(PITEST, "-Pharness.mutation.classes=com.ecommerce.demo.domain.NoSuchClass*")
                .output shouldBe ""
        }

        test("harness.pitest.arcmutate=true adds the Arcmutate Kotlin plugin, the default leaves it out") {
            val fixture = FixtureProject.prepare("pitest-strong")
            val report = arrayOf(":services:demo:domain:dependencies", "--configuration", "pitest")

            fixture.run(*report, "-Pharness.pitest.arcmutate=true").output shouldContain
                "com.arcmutate:pitest-kotlin-plugin"
            fixture.run(*report).output shouldNotContain "com.arcmutate"
        }
    
        test("a module outside services/ mutates the package it sets with mutation { targetPackage }") {
            val fixture = libraryWithPitest("\nmutation { targetPackage.set(\"com.ecommerce.platform\") }\n")

            fixture.run(LIBRARY_PITEST).output shouldBe ""

            fixture.mutatedClasses() shouldBe setOf("com.ecommerce.platform.StatusesKt")
        }

        test("harness.mutation.classes still wins over a module's target package") {
            val fixture = libraryWithPitest("\nmutation { targetPackage.set(\"com.ecommerce.platform\") }\n")

            fixture.run(LIBRARY_PITEST, "-Pharness.mutation.classes=com.ecommerce.platform.NoSuchClass*")

            fixture.mutatedClasses() shouldBe emptySet()
        }

        test("a module outside services/ without a target package fails at configuration") {
            libraryWithPitest().runAndFail("help").output shouldContain "mutation { targetPackage.set("
        }
    })
