import com.ecommerce.build.registerTestLayer
import org.springframework.boot.gradle.plugin.SpringBootPlugin

// Convention for a standalone Spring Boot WebFlux application without domain/application siblings (the gateway,
// :services:gateway): Boot BOM, WebFlux, actuator, coroutines, Jackson Kotlin, Prometheus, context propagation;
// the test layers test, integrationTest, contractTest and contractVerify (via `pact`) and acceptanceTest; the
// `dockerImage` task. kotlin-service builds on it for a service's `infrastructure` module.
plugins {
    id("kotlin-base")
    id("org.jetbrains.kotlin.plugin.spring")
    id("org.springframework.boot")
    id("pact")
    id("docker-image")
}

val catalog = the<VersionCatalogsExtension>().named("libs")

fun library(alias: String): Provider<MinimalExternalModuleDependency> = catalog.findLibrary(alias).get()

registerTestLayer("integrationTest")
registerTestLayer("acceptanceTest")

dependencies {
    "implementation"(platform(SpringBootPlugin.BOM_COORDINATES))
    listOf(
        "spring-boot-starter-webflux",
        "spring-boot-starter-actuator",
        "kotlinx-coroutines-reactor",
        "jackson-module-kotlin",
        "micrometer-registry-prometheus",
        "context-propagation",
    ).forEach { "implementation"(library(it)) }

    // testImplementation is inherited by every layer (TestLayers.kt)
    listOf("spring-boot-starter-webflux-test", "wiremock", "mockk", "konsist")
        .forEach { "testImplementation"(library(it)) }
    listOf("cucumber-java", "cucumber-spring", "cucumber-junit-platform-engine", "junit-platform-suite")
        .forEach { "acceptanceTestImplementation"(library(it)) }
}

springBoot {
    buildInfo()
}
