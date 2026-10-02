package com.ecommerce.build

import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import java.io.File
import java.util.concurrent.Callable

/** The JUnit tag of Pact provider verification classes: they run in `contractVerify`, never in `contractTest`. */
const val PROVIDER_TAG = "provider"

/** The system properties both contract tasks carry: where consumers write pacts and providers read them. */
fun Test.pactFolder(directory: File) {
    systemProperty("pact.rootDir", directory.path)
    systemProperty("pact.folder", directory.path)
    systemProperty("pact.writer.overwrite", "true")
}

/**
 * The paths of every `contractTest` task in the build, read while the task graph is built (after every project
 * is configured), so `contractVerify` can run after all consumers have written their pacts.
 */
fun Project.contractTestTasksOfTheBuild(): Callable<List<String>> =
    Callable {
        rootProject.allprojects
            .filter { "contractTest" in it.tasks.names }
            .map { if (it.path == ":") ":contractTest" else "${it.path}:contractTest" }
    }
