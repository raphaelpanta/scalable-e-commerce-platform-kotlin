package com.ecommerce.build

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private const val GATEWAY = ":services:gateway"

class KotlinBootAppPluginTest :
    FunSpec({
        test("a standalone Boot application builds with its test layers and no domain or application siblings") {
            val fixture = FixtureProject.prepare("boot-app-gateway")

            fixture.run("$GATEWAY:build").output shouldBe ""

            fixture.file("services/gateway/build/libs/services-gateway.jar").shouldExist()
            fixture.file("services/gateway/build/resources/main/META-INF/build-info.properties").shouldExist()
            val dryRun = fixture.run("$GATEWAY:check", "--dry-run").output
            listOf("test", "integrationTest", "contractTest", "contractVerify", "acceptanceTest").forEach {
                dryRun shouldContain "$GATEWAY:$it SKIPPED"
            }
        }

        test("dockerImage of a module that is not an infrastructure module is named after the module") {
            val fixture = FixtureProject.prepare("boot-app-gateway")
            val calls = fixture.dir.resolve("docker-calls.txt")
            val stub = fixture.stubExecutable("docker", "echo \"\$@\" >> '${calls.absolutePath}'")
            fixture.file("services/gateway/build.gradle.kts").appendText(
                "\ndocker { dockerExecutable.set(\"${stub.absolutePath}\") }\n",
            )

            fixture.run("$GATEWAY:dockerImage")

            calls.readText().trim() shouldBe
                "build -f platform/docker/Dockerfile --build-arg SERVICE_MODULE=$GATEWAY -t gateway:unspecified ."
        }
    })
