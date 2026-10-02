package com.ecommerce.build

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

private const val LIB = ":libs:sample"

class KotlinLibraryPluginTest :
    FunSpec({
        test("a library compiles Spring code without versions and checks its unit and integration layers") {
            val fixture = FixtureProject.prepare("library-demo")

            fixture.run("$LIB:check").output shouldBe ""

            val results = fixture.file("libs/sample/build/test-results")
            results.resolve("test/TEST-com.ecommerce.platform.StatusesTest.xml").shouldExist()
            results.resolve("integrationTest/TEST-com.ecommerce.platform.StatusesIntegrationTest.xml").shouldExist()
        }

        test("a library has test fixtures and the shared test libraries, but no Boot app, image or pitest") {
            val fixture = FixtureProject.prepare("library-demo")
            val classpath =
                fixture.run("$LIB:dependencies", "--configuration", "integrationTestRuntimeClasspath").output

            listOf(
                "io.kotest:kotest-runner-junit5",
                "io.mockk:mockk",
                "org.springframework.boot:spring-boot-starter-webflux-test",
                "org.springframework.boot:spring-boot-testcontainers",
                "org.testcontainers:testcontainers-postgresql",
                "org.testcontainers:testcontainers-junit-jupiter",
                "org.testcontainers:testcontainers-r2dbc",
                "org.wiremock:wiremock-standalone",
            ).forEach { classpath shouldContain it }

            val tasks = fixture.run("$LIB:tasks", "--all").output
            tasks shouldContain "compileTestFixturesKotlin"
            listOf("bootJar", "pitest", "dockerImage", "contractTest").forEach { tasks shouldNotContain "$it " }
        }
    })
