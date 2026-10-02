package com.ecommerce.build

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.gradle.testkit.runner.TaskOutcome
import java.io.File

class VersionLiteralCheckTest :
    FunSpec({
        test("module-level version literals fail with file, line, literal and a pointer to the catalogue") {
            val output = FixtureProject.prepare("version-literal-violation").runAndFail("checkVersionLiterals").output

            output shouldContain "libs/bad/build.gradle.kts:4"
            output shouldContain "org.example:lib:1.2.3"
            output shouldContain "jvmToolchain(25)"
            output shouldContain "toolVersion = \"0.9.1\""
            output shouldContain "id(\"com.example.plugin\") version \"1.0.0\""
            output shouldContain "gradle/libs.versions.toml"
        }

        test("catalogue references only pass silently") {
            FixtureProject.prepare("version-literal-clean").run("checkVersionLiterals").output shouldBe ""
        }

        test("the real repository contains no version literal") {
            VersionLiterals.scan(File(System.getProperty("repo.root"))).shouldBeEmpty()
        }

        test("the check is up to date on a second run") {
            val fixture = FixtureProject.prepare("version-literal-clean")
            fixture.run("checkVersionLiterals")

            fixture.run("checkVersionLiterals").result.task(":checkVersionLiterals")?.outcome shouldBe
                TaskOutcome.UP_TO_DATE
        }
    })
