// Gradle init script used only by platform/docker/Dockerfile.native (`./gradlew --init-script ...`): puts GraalVM Native
// Build Tools on the root build script class path and applies it to every Spring Boot application. Spring Boot reacts to
// the plugin itself (processAot, the `aot` source set and `nativeCompile` with the application's main class), so no build
// file of the repository changes and the JVM build, `verify` and the JVM images never see the plugin.
// The version lives in gradle/libs.versions.toml (`native-build-tools`); the Dockerfile passes it as
// NATIVE_BUILD_TOOLS_VERSION because an init script cannot read the version catalogue.
val nativeBuildTools: String =
    requireNotNull(System.getenv("NATIVE_BUILD_TOOLS_VERSION")?.takeIf { it.isNotBlank() }) {
        "NATIVE_BUILD_TOOLS_VERSION is not set (platform/docker/Dockerfile.native reads it from gradle/libs.versions.toml)"
    }

rootProject {
    buildscript {
        repositories {
            gradlePluginPortal()
            mavenCentral()
        }
        dependencies {
            classpath("org.graalvm.buildtools:native-gradle-plugin:$nativeBuildTools")
        }
    }
}

allprojects {
    pluginManager.withPlugin("org.springframework.boot") {
        pluginManager.apply("org.graalvm.buildtools.native")
    }
}
