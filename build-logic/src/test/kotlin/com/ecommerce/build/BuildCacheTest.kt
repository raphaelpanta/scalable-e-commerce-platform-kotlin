package com.ecommerce.build

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeIn
import org.gradle.testkit.runner.TaskOutcome

class BuildCacheTest :
    FunSpec({
        test("an unchanged second run reuses every result and an offline run succeeds") {
            // Every fixture build shares the TestKit GRADLE_USER_HOME, so the offline run reuses its downloads.
            val project = FixtureProject.prepare("verify-repo-green")
            project.run("verify")

            val second = project.run("verify").result

            listOf(":libs:alpha:test", ":libs:beta:test", ":checkToolchain").forEach { path ->
                second.task(path)?.outcome shouldBeIn listOf(TaskOutcome.UP_TO_DATE, TaskOutcome.FROM_CACHE)
            }
            project.run("--offline", "verify")
        }
    })
