package com.ecommerce.build

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class KotlinBasePluginTest :
    FunSpec({
        test("compiles, runs Kotest specs silently and pins the JDK 25 toolchain with warnings as errors") {
            val output = FixtureProject.prepare("kotlin-base-ok").run("test", "printToolchain").output

            output.lines().filter(String::isNotBlank) shouldBe
                listOf("toolchain=25", "allWarningsAsErrors=true", "archivesName=kotlin-base-ok")
        }

        test("a compiler warning fails compileKotlin and shows the warning") {
            val output = FixtureProject.prepare("kotlin-base-warning").runAndFail("compileKotlin").output

            output shouldContain "Warn.kt"
            output shouldContain "deprecated"
        }

        test("the test runtime classpath carries the Kotest JUnit 5 runner") {
            FixtureProject
                .prepare("kotlin-base-ok")
                .run("dependencies", "--configuration", "testRuntimeClasspath")
                .output shouldContain "io.kotest:kotest-runner-junit5"
        }

        test("archivesName is the project path without the leading colon and with dashes") {
            val fixture =
                FixtureProject.prepare(
                    "kotlin-base-ok",
                    mapOf("libs/alpha/build.gradle.kts" to "plugins {\n    id(\"kotlin-base\")\n}\n"),
                )
            fixture.file("settings.gradle.kts").appendText("include(\":libs:alpha\")\n")
            fixture.file("build.gradle.kts").appendText(
                """
                evaluationDependsOn(":libs:alpha")
                tasks.register("printAlphaArchivesName") {
                    val alphaArchives = project(":libs:alpha").the<BasePluginExtension>().archivesName
                    doLast { println("alpha=" + alphaArchives.get()) }
                }
                """.trimIndent(),
            )

            fixture.run("printAlphaArchivesName").output.trim() shouldBe "alpha=libs-alpha"
        }
    })
