package com.ecommerce.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.Properties

private const val JAVA_VERSION = ".java-version"
private const val SDKMANRC = ".sdkmanrc"
private const val DAEMON_JVM = "gradle/gradle-daemon-jvm.properties"
private const val WRAPPER = "gradle/wrapper/gradle-wrapper.properties"
private val WRAPPER_DISTRIBUTION = Regex("""gradle-([^/]+?)-(?:bin|all)\.zip""")

/**
 * Fails when a JDK or Gradle pin disagrees with the catalogue's `jdk` and `gradle` versions (FR-010): the
 * `.java-version`, `.sdkmanrc` and daemon JVM files must name the catalogue JDK, the wrapper must download the
 * catalogue Gradle, and the build must be running on that Gradle.
 */
@CacheableTask
abstract class ToolchainConsistencyTask : DefaultTask() {
    /** `[versions] jdk` of `gradle/libs.versions.toml`. */
    @get:Input
    abstract val jdk: Property<String>

    /** `[versions] gradle` of `gradle/libs.versions.toml`. */
    @get:Input
    abstract val gradle: Property<String>

    /** The version of the Gradle running this build. */
    @get:Input
    abstract val runningGradle: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val javaVersionFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sdkmanrcFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val daemonJvmFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val wrapperPropertiesFile: RegularFileProperty

    @get:OutputFile
    abstract val marker: RegularFileProperty

    @TaskAction
    fun check() {
        val requiredJdk = jdk.get()
        val requiredGradle = gradle.get()
        val jdkPins =
            mapOf(
                JAVA_VERSION to javaVersionFile.get().asFile.readText().trim(),
                SDKMANRC to sdkmanJava(sdkmanrcFile.get().asFile),
                DAEMON_JVM to properties(daemonJvmFile.get().asFile).getProperty("toolchainVersion").orEmpty(),
            )
        val wrapperGradle =
            WRAPPER_DISTRIBUTION
                .find(properties(wrapperPropertiesFile.get().asFile).getProperty("distributionUrl").orEmpty())
                ?.groupValues
                ?.get(1)
                .orEmpty()
        val mismatches =
            jdkPins
                .filter { (_, value) -> majorVersion(value) != requiredJdk }
                .map { (file, value) ->
                    "Required JDK $requiredJdk (gradle/libs.versions.toml [versions] jdk) but $file says $value"
                } +
                listOfNotNull(
                    "Required Gradle $requiredGradle (run ./gradlew) but $WRAPPER says $wrapperGradle"
                        .takeIf { wrapperGradle != requiredGradle },
                    "Required Gradle $requiredGradle (run ./gradlew) but this build runs on Gradle ${runningGradle.get()}"
                        .takeIf { runningGradle.get() != requiredGradle },
                )
        if (mismatches.isNotEmpty()) {
            throw GradleException(mismatches.joinToString(separator = "\n"))
        }
        marker.get().asFile.writeText("ok\n")
    }

    private fun properties(file: File): Properties = Properties().apply { file.reader().use(::load) }

    private fun sdkmanJava(file: File): String =
        file
            .readLines()
            .map(String::trim)
            .firstOrNull { it.startsWith("java=") }
            ?.substringAfter('=')
            .orEmpty()

    /** `25` for `25`, `25.0.4-tem` or `25.0.4`. */
    private fun majorVersion(value: String): String = value.substringBefore('-').substringBefore('.')
}
