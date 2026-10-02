package com.ecommerce.build

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContainIgnoringCase

class KotlinApplicationPluginTest :
    FunSpec({
        test("application depends on its sibling domain automatically and carries no framework") {
            val fixture = FixtureProject.prepare("layers-boundaries")

            fixture
                .run(":services:demo:application:dependencies", "--configuration", "compileClasspath")
                .output shouldContain "project ':services:demo:domain'"
            fixture
                .run(":services:demo:application:dependencies", "--configuration", "runtimeClasspath")
                .output shouldNotContainIgnoringCase "org.springframework"
        }

        test("a dependency on the adapters fails at configuration") {
            val output = FixtureProject.prepare("layers-application-violation").runAndFail("help").output

            output shouldContain "application must not depend on adapters"
            output shouldContain ":services:demo:infrastructure"
        }
    })
