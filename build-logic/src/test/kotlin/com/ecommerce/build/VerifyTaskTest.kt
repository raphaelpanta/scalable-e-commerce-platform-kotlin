package com.ecommerce.build

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class VerifyTaskTest :
    FunSpec({
        test("verify on a green repository succeeds and prints nothing (SC-002 allows at most 5 lines)") {
            val output = FixtureProject.prepare("verify-repo-green").run("verify").output

            output.lines().count { it.isNotBlank() } shouldBe 0
        }

        test("verify runs the toolchain check, the included build's check and the check of every module") {
            val output = FixtureProject.prepare("verify-repo-green").run("verify", "--dry-run").output

            output shouldContain ":checkToolchain SKIPPED"
            output shouldContain ":checkVersionLiterals SKIPPED"
            output shouldContain ":build-logic:check SKIPPED"
            output shouldContain ":libs:alpha:check SKIPPED"
            output shouldContain ":libs:beta:check SKIPPED"
        }

        test("verify fails when a module test fails") {
            FixtureProject.prepare("verify-repo-failing-test").runAndFail("verify")
        }
    })
