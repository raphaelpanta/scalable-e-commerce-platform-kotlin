package com.ecommerce.build

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.util.GradleVersion

private const val DAEMON_JVM = "gradle/gradle-daemon-jvm.properties"
private const val WRAPPER = "gradle/wrapper/gradle-wrapper.properties"

/** The `[versions]` value of [key] in the catalogue copied into [project]. */
private fun catalogVersion(
    project: FixtureProject,
    key: String,
): String =
    project
        .file("gradle/libs.versions.toml")
        .readLines()
        .first { it.trimStart().startsWith("$key =") }
        .substringAfter('"')
        .substringBefore('"')

private fun FixtureProject.replaceIn(
    path: String,
    old: String,
    new: String,
) {
    val content = file(path).readText()
    require(old in content) { "$path does not contain $old" }
    write(path, content.replace(old, new))
}

class ToolchainConsistencyTest :
    FunSpec({
        test("matching pins pass") {
            FixtureProject.prepare("verify-repo-green").run("checkToolchain")
        }

        test("a different JDK in .java-version fails naming the file") {
            val project = FixtureProject.prepare("verify-repo-green")
            project.write(".java-version", "24\n")

            val output = project.runAndFail("checkToolchain").output

            output shouldContain "Required JDK ${catalogVersion(project, "jdk")}"
            output shouldContain ".java-version"
        }

        test("a different JDK in .sdkmanrc fails naming the file") {
            val project = FixtureProject.prepare("verify-repo-green")
            project.write(".sdkmanrc", "java=24.0.2-tem\n")

            val output = project.runAndFail("checkToolchain").output

            output shouldContain "Required JDK ${catalogVersion(project, "jdk")}"
            output shouldContain ".sdkmanrc"
        }

        test("a different Gradle in the wrapper properties fails naming the required version") {
            val project = FixtureProject.prepare("verify-repo-green")
            val gradle = catalogVersion(project, "gradle")
            project.replaceIn(WRAPPER, "gradle-$gradle-bin.zip", "gradle-9.7.0-bin.zip")

            val output = project.runAndFail("checkToolchain").output

            output shouldContain "Gradle $gradle"
            output shouldContain WRAPPER
        }

        test("a different daemon JVM fails naming the daemon JVM properties") {
            val project = FixtureProject.prepare("verify-repo-green")
            val jdk = catalogVersion(project, "jdk")
            project.replaceIn(DAEMON_JVM, "toolchainVersion=$jdk", "toolchainVersion=24")
            // TestKit cannot start a daemon whose criteria ask for an absent JDK, so the task runs in-process.
            val task =
                ProjectBuilder
                    .builder()
                    .withProjectDir(project.dir)
                    .build()
                    .tasks
                    .register("checkToolchain", ToolchainConsistencyTask::class.java) {
                        this.jdk.set(jdk)
                        gradle.set(GradleVersion.current().version)
                        runningGradle.set(GradleVersion.current().version)
                        javaVersionFile.set(project.file(".java-version"))
                        sdkmanrcFile.set(project.file(".sdkmanrc"))
                        daemonJvmFile.set(project.file(DAEMON_JVM))
                        wrapperPropertiesFile.set(project.file(WRAPPER))
                        marker.set(project.file("build/toolchain/ok"))
                    }.get()

            val message = shouldThrow<GradleException> { task.check() }.message.orEmpty()

            message shouldContain "Required JDK $jdk"
            message shouldContain DAEMON_JVM
            message shouldNotContain WRAPPER
        }
    })
