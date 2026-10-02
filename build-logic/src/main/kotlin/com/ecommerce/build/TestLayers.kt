package com.ecommerce.build

import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Project
import org.gradle.api.plugins.jvm.JvmTestSuite
import org.gradle.kotlin.dsl.getByType
import org.gradle.testing.base.TestingExtension

/** The test layers of a service module, in the order they run (constitution Principle V). */
val TEST_LAYERS: List<String> = listOf("test", "integrationTest", "contractTest", "acceptanceTest")

/**
 * Registers the JVM test suite [name] (`integrationTest`, `contractTest` or `acceptanceTest`) with its own
 * `src/<name>/kotlin` source set and task, the main output and the unit layer's dependencies on its
 * classpath, ordering after the earlier layers the module has, and `check` depending on it. Idempotent.
 */
fun Project.registerTestLayer(name: String): NamedDomainObjectProvider<JvmTestSuite> {
    require(name in TEST_LAYERS && name != "test") { "unknown test layer $name; expected one of $TEST_LAYERS" }
    pluginManager.apply("jvm-test-suite")
    val suites = extensions.getByType<TestingExtension>().suites
    if (name in suites.names) return suites.named(name, JvmTestSuite::class.java)

    val earlier = TEST_LAYERS.subList(0, TEST_LAYERS.indexOf(name)).toSet()
    val earlierLayers = tasks.named { it in earlier }
    val suite =
        suites.register(name, JvmTestSuite::class.java) {
            useJUnitJupiter()
            dependencies {
                implementation.add(project())
            }
            targets.configureEach {
                testTask.configure {
                    shouldRunAfter(earlierLayers)
                    failOnNoDiscoveredTests.set(false)
                }
            }
        }
    suite.get() // realise the source set so its configurations exist for the caller's dependencies
    configurations.named("${name}Implementation") { extendsFrom(configurations.getByName("testImplementation")) }
    configurations.named("${name}RuntimeOnly") { extendsFrom(configurations.getByName("testRuntimeOnly")) }
    tasks.named("check") { dependsOn(suite) }
    return suite
}
