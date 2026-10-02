package com.ecommerce.build

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

private const val INFRA = ":services:demo:infrastructure"

class DockerImagePluginTest :
    FunSpec({
        // Exec resolves a bare executable name against the daemon's original PATH, so the stub is wired
        // through the convention's dockerExecutable property instead of the PATH.
        fun FixtureProject.recordDocker() =
            dir.resolve("docker-calls.txt").also { calls ->
                val stub = stubExecutable("docker", "echo \"\$@\" >> '${calls.absolutePath}'")
                file("services/demo/infrastructure/build.gradle.kts").appendText(
                    "\ndocker { dockerExecutable.set(\"${stub.absolutePath}\") }\n",
                )
            }

        test("dockerImage builds the shared Dockerfile for the module") {
            val fixture = FixtureProject.prepare("docker-image-demo")
            val calls = fixture.recordDocker()

            fixture.run("$INFRA:dockerImage")

            calls.readText().trim() shouldBe
                "build -f platform/docker/Dockerfile --build-arg SERVICE_MODULE=$INFRA -t demo:unspecified ."
        }

        test("dockerImage fails when the shared Dockerfile is missing") {
            val fixture = FixtureProject.prepare("docker-image-no-dockerfile")
            fixture.recordDocker()

            fixture.runAndFail("$INFRA:dockerImage").output shouldContain "platform/docker/Dockerfile not found"
        }

        test("dockerImage is not part of check or verify") {
            FixtureProject.prepare("docker-image-demo").run("check", "verify", "--dry-run").output shouldNotContain
                "dockerImage"
        }
    })
