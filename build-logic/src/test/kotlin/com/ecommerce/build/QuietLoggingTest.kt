package com.ecommerce.build

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class QuietLoggingTest :
    FunSpec({
        test("a failing test prints only the failing test, its full assertion and its module") {
            val output = FixtureProject.prepare("verify-repo-failing-test").runAndFail("verify").output

            output shouldContain "sum"
            output shouldContain "expected:<2> but was:<3>"
            output shouldContain ":libs:alpha:test"
            output shouldNotContain "betaPassesQuietly"
            output shouldNotContain "PASSED"
            output shouldNotContain "\u001B"
        }

        test("a green build prints nothing") {
            FixtureProject.prepare("verify-repo-green").run("verify").output.trim() shouldBe ""
        }
    })
