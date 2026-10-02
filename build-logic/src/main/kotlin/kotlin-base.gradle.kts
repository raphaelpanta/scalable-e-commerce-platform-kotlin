import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

// Internal convention shared by kotlin-domain, kotlin-application, kotlin-service, kotlin-boot-app and
// kotlin-library; modules never apply it directly. Kotlin JVM on the catalogue's JDK toolchain, warnings as
// errors, Kotest on JUnit 5.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("quality")
}

val catalog = the<VersionCatalogsExtension>().named("libs")
val jdk: Int =
    catalog
        .findVersion("jdk")
        .get()
        .requiredVersion
        .toInt()

kotlin {
    jvmToolchain(jdk)
    compilerOptions {
        allWarningsAsErrors.set(true)
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(jdk))
    }
}

base.archivesName.set(if (path == ":") name else path.removePrefix(":").replace(':', '-'))

dependencies {
    "testImplementation"(catalog.findBundle("kotest").get())
    "testRuntimeOnly"(catalog.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // JDK 25 warns when mocking agents attach at run time, when class data sharing meets an appended boot
    // class path, when Netty loads its native transport and when Konsist's Kotlin compiler uses
    // sun.misc.Unsafe; these flags keep every test layer silent under -q (Principle VIII).
    jvmArgs(
        "-XX:+EnableDynamicAgentLoading",
        "-Xshare:off",
        "--enable-native-access=ALL-UNNAMED",
        "--sun-misc-unsafe-memory-access=allow",
    )
    failOnNoDiscoveredTests.set(false)
    // Principle VIII: only failures, with the full assertion. Test events are logged per log level and
    // org.gradle.logging.level=quiet hides the lifecycle level, so the failure report is configured for quiet.
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
