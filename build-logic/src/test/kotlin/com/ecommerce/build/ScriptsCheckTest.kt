package com.ecommerce.build

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File

/** A stub `scripts/tests/run-all.sh` that records its arguments and fails when [fail]. */
private fun FixtureProject.stubScriptTests(fail: Boolean = false): File {
    val calls = file("script-test-calls.txt")
    val exit = if (fail) "\necho 'FAIL: 1 of 3 tests failed' >&2\nexit 1" else ""
    write("scripts/tests/run-all.sh", "#!/bin/sh\necho \"\$*\" >> '${calls.absolutePath}'$exit\n").setExecutable(true)
    write("scripts/lint.sh", "#!/bin/sh\nexit 0\n").setExecutable(true)
    return calls
}

class ScriptsCheckTest :
    FunSpec({
        test("without scripts/tests/run-all.sh there is no scriptsCheck task") {
            val output = FixtureProject.prepare("verify-repo-green").run("tasks", "--all").output

            output shouldContain "verify"
            output shouldNotContain "scriptsCheck"
        }

        test("verify runs the script tests quietly through scriptsCheck") {
            val project = FixtureProject.prepare("verify-repo-green")
            val calls = project.stubScriptTests()

            val output = project.run("verify").output

            calls.readLines() shouldBe listOf("--quiet")
            output.trim() shouldBe ""
        }

        test("failing script tests fail verify and name the task") {
            val project = FixtureProject.prepare("verify-repo-green")
            project.stubScriptTests(fail = true)

            val output = project.runAndFail("verify").output

            output shouldContain "scriptsTest"
            output shouldContain "FAIL: 1 of 3 tests failed"
        }
    })
