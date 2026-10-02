package com.ecommerce.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails when any `*.gradle.kts` file declares a version outside `gradle/libs.versions.toml` (FR-004).
 * Every hit is listed as `<path>:<line>: version literal '<text>'; declare it in gradle/libs.versions.toml ...`.
 */
@CacheableTask
abstract class CheckVersionLiteralsTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val scripts: ConfigurableFileCollection

    @get:Internal
    abstract val rootDirectory: DirectoryProperty

    @get:OutputFile
    abstract val marker: RegularFileProperty

    @TaskAction
    fun check() {
        val literals = VersionLiterals.find(rootDirectory.get().asFile, scripts.files)
        if (literals.isNotEmpty()) {
            throw GradleException(literals.joinToString(separator = "\n"))
        }
        marker.get().asFile.writeText("ok\n")
    }
}
