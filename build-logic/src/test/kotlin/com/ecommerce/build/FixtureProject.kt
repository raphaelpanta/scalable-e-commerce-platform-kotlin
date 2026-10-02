package com.ecommerce.build

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import java.io.File
import java.io.StringWriter
import java.nio.file.Files

/** Output of one fixture build: stdout and stderr combined, plus the TestKit result. */
data class FixtureRun(
    val output: String,
    val result: BuildResult,
)

/**
 * A small Gradle build copied from `build-logic/src/test/resources/fixtures/<name>` into a temporary
 * directory together with the real repository pins, run through Gradle TestKit with the convention
 * plugins of this included build on its classpath.
 */
class FixtureProject private constructor(
    val dir: File,
) {
    private val binDir: File = temporaryDirectory("fixture-bin-")

    fun file(path: String): File = dir.resolve(path)

    fun write(
        path: String,
        content: String,
    ): File =
        file(path).apply {
            parentFile.mkdirs()
            writeText(content)
        }

    /** Writes an executable shell script named [name] to a directory prepended to the build's PATH. */
    fun stubExecutable(
        name: String,
        script: String,
    ): File =
        binDir.resolve(name).apply {
            writeText("#!/bin/sh\n$script\n")
            setExecutable(true)
        }

    fun run(vararg args: String): FixtureRun = execute(args, expectFailure = false)

    fun runAndFail(vararg args: String): FixtureRun = execute(args, expectFailure = true)

    fun runner(vararg args: String): GradleRunner {
        val path = listOfNotNull(binDir.absolutePath, System.getenv("PATH")).joinToString(File.pathSeparator)
        return GradleRunner
            .create()
            .withProjectDir(dir)
            .withPluginClasspath()
            .withTestKitDir(testKitDir)
            .withEnvironment(System.getenv() + ("PATH" to path))
            .withArguments("-q", "--stacktrace", *args)
    }

    private fun execute(
        args: Array<out String>,
        expectFailure: Boolean,
    ): FixtureRun {
        val out = StringWriter()
        val runner = runner(*args).forwardStdOutput(out).forwardStdError(out)
        val result = if (expectFailure) runner.buildAndFail() else runner.build()
        return FixtureRun(out.toString(), result)
    }

    companion object {
        private val repoRoot: File =
            File(
                requireNotNull(System.getProperty("repo.root")) { "system property repo.root is not set" },
            )

        private val testKitDir: File = File("build/testkit").absoluteFile

        private val createdDirectories = mutableListOf<File>()

        init {
            Runtime.getRuntime().addShutdownHook(
                Thread { synchronized(createdDirectories) { createdDirectories.forEach { it.deleteRecursively() } } },
            )
        }

        /** A temporary directory removed when the test JVM exits. */
        private fun temporaryDirectory(prefix: String): File {
            val directory = Files.createTempDirectory(prefix).toFile()
            synchronized(createdDirectories) { createdDirectories += directory }
            return directory
        }

        private val pinnedFiles =
            listOf(
                "gradle/libs.versions.toml",
                ".editorconfig",
                ".java-version",
                ".sdkmanrc",
                "config/detekt/detekt.yml",
                "gradle/gradle-daemon-jvm.properties",
                "gradle/wrapper/gradle-wrapper.properties",
            )

        fun prepare(
            name: String,
            extraFiles: Map<String, String> = emptyMap(),
        ): FixtureProject {
            val source = repoRoot.resolve("build-logic/src/test/resources/fixtures/$name")
            require(source.isDirectory) { "fixture $name not found at $source" }
            val dir = temporaryDirectory("fixture-$name-")
            source.copyRecursively(dir)
            pinnedFiles.forEach { pinned -> repoRoot.resolve(pinned).copyTo(dir.resolve(pinned), overwrite = true) }
            dir.resolve("settings.gradle.kts").appendText(
                "\ndependencyResolutionManagement { repositories { mavenCentral() } }\n",
            )
            writeGradleProperties(dir)
            return FixtureProject(dir).apply { extraFiles.forEach { (path, content) -> write(path, content) } }
        }

        /**
         * The real repository settings plus the location of the JDK running this test (the pinned JDK 25,
         * provisioned for build-logic), so fixture builds resolve their toolchain without downloading.
         */
        private fun writeGradleProperties(dir: File) {
            val properties = dir.resolve("gradle.properties")
            val base = repoRoot.resolve("gradle.properties").readText()
            val javaHome = File(System.getProperty("java.home")).absolutePath
            properties.writeText(
                base
                    .lineSequence()
                    .filterNot { it.startsWith("org.gradle.java.installations.") }
                    .joinToString("\n") +
                    "\norg.gradle.java.installations.auto-download=false" +
                    "\norg.gradle.java.installations.paths=$javaHome\n",
            )
        }
    }
}
