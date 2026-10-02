package com.ecommerce.build

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/** A stub `npm` that records its arguments, one call per line, and fails on `run test` when [failTest]. */
private fun FixtureProject.stubNpm(failTest: Boolean = false): Pair<String, java.io.File> {
    val calls = file("npm-calls.txt")
    val failure = if (failTest) "\ncase \"\$*\" in *\"run test\"*) exit 1 ;; esac" else ""
    val npm = stubExecutable("npm", "echo \"\$*\" >> '${calls.absolutePath}'$failure")
    return "-PnpmExecutable=${npm.absolutePath}" to calls
}

class FrontendHookTest :
    FunSpec({
        test("without frontend/package.json there are no frontend tasks") {
            val output = FixtureProject.prepare("verify-repo-green").run("tasks", "--all").output

            output shouldContain "verify"
            output shouldNotContain "frontendCheck"
        }

        test("verify runs the frontend lint and test scripts silently, lint first") {
            val project = FixtureProject.prepare("verify-repo-frontend")
            val (npm, calls) = project.stubNpm()

            project.run("verify", npm)

            calls.readLines() shouldBe
                listOf("--silent --prefix frontend run lint", "--silent --prefix frontend run test")
        }

        test("a failing frontend test fails verify") {
            val project = FixtureProject.prepare("verify-repo-frontend")
            val (npm, _) = project.stubNpm(failTest = true)

            project.runAndFail("verify", npm).output shouldContain "frontendTest"
        }
    })
