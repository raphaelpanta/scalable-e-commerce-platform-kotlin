import com.sun.management.OperatingSystemMXBean
import dev.detekt.gradle.Detekt
import dev.detekt.gradle.extensions.FailOnSeverity
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType
import java.lang.management.ManagementFactory

plugins {
    `kotlin-dsl`
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

val jdkRelease: Provider<Int> = libs.versions.jdk.map(String::toInt)

kotlin {
    jvmToolchain(jdkRelease.get())
}

ktlint {
    version.set(libs.versions.ktlint.asProvider())
    ignoreFailures.set(false)
    coloredOutput.set(false)
    outputToConsole.set(true)
    reporters {
        reporter(ReporterType.PLAIN)
    }
}

// kotlin-dsl generates accessor sources under build/; only hand-written code is linted
tasks.withType<org.jlleitschuh.gradle.ktlint.tasks.BaseKtLintCheckTask>().configureEach {
    exclude { element -> element.file.invariantSeparatorsPath.contains("/build/") }
}

detekt {
    buildUponDefaultConfig.set(true)
    config.setFrom(rootDir.resolve("../config/detekt/detekt.yml"))
    ignoreFailures.set(false)
    failOnSeverity.set(FailOnSeverity.Info)
}

tasks.withType<Detekt>().configureEach {
    reports {
        checkstyle.required.set(false)
        html.required.set(false)
        sarif.required.set(false)
        markdown.required.set(false)
    }
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.allopen.gradle.plugin)
    implementation(libs.spring.boot.gradle.plugin)
    implementation(libs.ktlint.gradle.plugin)
    implementation(libs.detekt.gradle.plugin)
    implementation(libs.pitest.gradle.plugin)

    testImplementation(libs.bundles.kotest)
    testImplementation(gradleTestKit())
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("repo.root", rootDir.parentFile.absolutePath)
    // Every fork drives TestKit builds with their own Gradle and Kotlin daemons, outside the main build's
    // HeavyTaskLimit: one fork where memory is short (the CI runner's 10 GB cap, shared with the main build in
    // verify), two from 12 GiB up. The test JVM itself only drives TestKit; without a bound it takes a quarter of the
    // machine's memory.
    val totalMemoryGib =
        (ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean).totalMemorySize shr 30
    maxParallelForks = if (totalMemoryGib >= 12) 2 else 1
    maxHeapSize = "768m"
    jvmArgs("-XX:+EnableDynamicAgentLoading")
    failOnNoDiscoveredTests.set(false)
    // Same quiet failure report as the kotlin-base convention (Principle VIII).
    testLogging {
        events = emptySet()
        showStandardStreams = false
        quiet {
            events(TestLogEvent.FAILED)
            exceptionFormat = TestExceptionFormat.FULL
            showStandardStreams = false
        }
    }
}

// ktlint 1.8 ships a Kotlin compiler that prints a sun.misc.Unsafe warning on JDK 25; the compiler
// embedded in the pinned Gradle version does not, and the worker JVM arguments are not configurable.
configurations.matching { it.name == "ktlint" }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") useVersion(embeddedKotlinVersion)
    }
}
