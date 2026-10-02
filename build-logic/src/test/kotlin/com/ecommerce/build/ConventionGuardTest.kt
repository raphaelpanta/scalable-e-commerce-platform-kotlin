package com.ecommerce.build

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class ConventionGuardTest :
    FunSpec({
        test("a leaf module that applies no convention fails the build; group projects are ignored") {
            val output = FixtureProject.prepare("convention-missing").runAndFail("help").output

            output shouldContain ":libs:naked"
            output shouldContain "apply a convention"
            output shouldNotContain "Module :services "
            output shouldNotContain "Module :services:demo "
            output shouldNotContain "Module :libs "
        }

        test("an exempted module passes") {
            FixtureProject.prepare("convention-exempt").run("help")
        }
    })
