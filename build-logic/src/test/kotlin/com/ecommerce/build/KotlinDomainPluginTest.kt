package com.ecommerce.build

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldNotContainIgnoringCase

class KotlinDomainPluginTest :
    FunSpec({
        test("domain carries only the unit test layer and no framework") {
            val fixture = FixtureProject.prepare("layers-boundaries")
            val tasks = fixture.run(":services:demo:domain:tasks", "--all").output

            tasks shouldContain "\ntest - "
            listOf("integrationTest", "contractTest", "acceptanceTest").forEach { tasks shouldNotContain "\n$it - " }
            fixture
                .run(":services:demo:domain:dependencies", "--configuration", "runtimeClasspath")
                .output shouldNotContainIgnoringCase "org.springframework"
        }

        test("a project dependency from domain fails at configuration") {
            val output = FixtureProject.prepare("layers-domain-violation").runAndFail("help").output

            output shouldContain "domain must not depend on"
            output shouldContain ":services:demo:application"
        }
    })
