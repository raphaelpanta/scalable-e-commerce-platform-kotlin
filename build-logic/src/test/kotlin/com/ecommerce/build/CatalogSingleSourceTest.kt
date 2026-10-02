package com.ecommerce.build

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class CatalogSingleSourceTest :
    FunSpec({
        test("every module follows a version change made only in the catalogue") {
            val fixture = FixtureProject.prepare("catalog-single-source")
            val scripts = listOf("libs/one/build.gradle.kts", "libs/two/build.gradle.kts")
            val scriptsBefore = scripts.map { fixture.file(it).readText() }
            val catalogue = fixture.file("gradle/libs.versions.toml")
            val current = Regex("^kotest = \"([^\"]+)\"", RegexOption.MULTILINE).find(catalogue.readText())
            val currentVersion = current?.groupValues?.get(1)

            fun resolved(module: String): String =
                fixture.run(":libs:$module:dependencies", "--configuration", "testRuntimeClasspath").output

            listOf("one", "two").forEach {
                resolved(it) shouldContain "io.kotest:kotest-assertions-core:$currentVersion"
            }

            catalogue.writeText(catalogue.readText().replace("kotest = \"$currentVersion\"", "kotest = \"6.2.4\""))

            listOf("one", "two").forEach { resolved(it) shouldContain "io.kotest:kotest-assertions-core:6.2.4" }
            scripts.map { fixture.file(it).readText() } shouldBe scriptsBefore
        }
    })
