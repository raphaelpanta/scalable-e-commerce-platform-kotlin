package com.ecommerce.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Echoes one ktlint check task's plain report at the QUIET log level. The ktlint plugin logs findings at WARN,
 * which `org.gradle.logging.level=quiet` hides, so without this a style failure would only name a report file.
 * Runs as the finalizer of that check task and prints nothing when the report is empty. A printed report is
 * deleted afterwards, so a later build never echoes stale findings; the check task that wrote it failed and
 * therefore runs again next time.
 */
@DisableCachingByDefault(because = "Only prints existing reports to the console")
abstract class KtlintFindingsReport : DefaultTask() {
    /** The check task's report directory; read only at execution, so it is not a tracked input of a finalizer. */
    @get:Internal
    abstract val reportsDirectory: DirectoryProperty

    @TaskAction
    fun echo() {
        reportsDirectory
            .get()
            .asFile
            .walkTopDown()
            .filter { it.isFile && it.extension == "txt" }
            .sorted()
            .forEach { report ->
                val findings = report.readLines().filter { it.isNotBlank() }
                if (findings.isNotEmpty()) {
                    findings.forEach { line -> logger.quiet(line) }
                    report.delete()
                }
            }
    }
}
