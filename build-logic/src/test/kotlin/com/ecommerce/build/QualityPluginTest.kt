package com.ecommerce.build

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class QualityPluginTest :
    FunSpec({
        test("a clean project passes check with no output") {
            FixtureProject.prepare("quality-clean").run("check").output shouldBe ""
        }

        test("a ktlint violation fails check and names the offending file") {
            FixtureProject.prepare("quality-ktlint-violation").runAndFail("check").output shouldContain "Bad.kt"
        }

        test("ktlint findings of an earlier failing run are not printed again once the code is fixed") {
            val fixture = FixtureProject.prepare("quality-ktlint-violation")
            // Without the build cache: ktlint-gradle's incremental findings carry the absolute paths of the
            // fixture directory that first cached them, and every fixture lives in a new temporary directory.
            fixture.runAndFail("ktlintCheck", "--no-build-cache").output shouldContain "Bad.kt"

            fixture.write("src/main/kotlin/Bad.kt", "fun names(): List<String> = listOf(\"a\")\n")
            fixture.run("ktlintCheck", "--no-build-cache").output shouldBe ""
            fixture.run("ktlintCheck", "--no-build-cache", "--rerun-tasks").output shouldBe ""
        }

        test("a detekt violation fails check and names the rule") {
            FixtureProject
                .prepare("quality-detekt-violation")
                .runAndFail("check")
                .output shouldContain "UnsafeCallOnNullableType"
        }

        test("the ktlintCheck and detekt tasks exist") {
            val output = FixtureProject.prepare("quality-clean").run("ktlintCheck", "detekt", "--dry-run").output

            output shouldContain ":ktlintCheck SKIPPED"
            output shouldContain ":detekt SKIPPED"
        }
    })
