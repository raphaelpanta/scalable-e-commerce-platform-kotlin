package com.ecommerce.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import java.io.File

/**
 * `./gradlew newService -Pname=<context>` (FR-012): copies `service-template/` from this build's resources to
 * `services/<name>` (the layout of the reference service `services/catalog`, with one-line module scripts) and
 * registers the service with `includeService("<name>")` below the registry marker of `settings.gradle.kts`.
 * Refuses an invalid name or an existing service before writing anything.
 */
@UntrackedTask(because = "Creates a service in the repository once; there is nothing to cache or skip")
abstract class ScaffoldServiceTask : DefaultTask() {
    /** The bounded context, from `-Pname`; absent when the property is not given. */
    @get:Internal
    abstract val serviceName: Property<String>

    /** The repository root holding `settings.gradle.kts` and `services/`. */
    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    @TaskAction
    fun scaffold() {
        val name = serviceName.orNull ?: refuse("-Pname=<context> is required: ./gradlew newService -Pname=<context>")
        if (!NAME_PATTERN.matches(name)) refuse("Invalid service name '$name': name must match $NAME_RULE")
        val root = repositoryRoot.get().asFile
        val settings = root.resolve("settings.gradle.kts")
        val text = settings.readText()
        val registration = "includeService(\"$name\")"
        val target = root.resolve("services/$name")
        if (target.exists() || text.lines().any { it.trim() == registration }) {
            refuse("Service '$name' already exists (services/$name or $registration in settings.gradle.kts)")
        }
        val updatedSettings = register(text, registration)

        copyTemplate(target, placeholders(name))
        settings.writeText(updatedSettings)
        logger.quiet("Created services/$name and registered $registration in settings.gradle.kts")
    }

    private fun copyTemplate(
        target: File,
        placeholders: Map<String, String>,
    ) {
        fun String.filled(): String = placeholders.entries.fold(this) { acc, (key, value) -> acc.replace(key, value) }
        templateFiles().forEach { path ->
            val content = template("$TEMPLATE_ROOT/$path").filled()
            target.resolve(path.filled()).apply {
                parentFile.mkdirs()
                writeText(content)
            }
        }
    }

    private fun templateFiles(): List<String> = template("$TEMPLATE_ROOT/index.txt").lines().filter { it.isNotBlank() }

    private fun template(resource: String): String =
        requireNotNull(ScaffoldServiceTask::class.java.getResource("/$resource")) { "missing template $resource" }
            .readText()

    companion object {
        /** The line of `settings.gradle.kts` below which services are registered. */
        const val MARKER = "// --- includeService registry (the newService task appends below this line) ---"
        const val NAME_RULE = "[a-z][a-z0-9]*"
        private const val TEMPLATE_ROOT = "service-template"
        private val NAME_PATTERN = Regex(NAME_RULE)

        private fun refuse(message: String): Nothing = throw GradleException(message)

        /** `__name__` (orders), `__Name__` (Orders) and `__NAME__` (ORDERS) in template paths and contents. */
        fun placeholders(name: String): Map<String, String> =
            mapOf(
                "__name__" to name,
                "__Name__" to name.replaceFirstChar(Char::uppercaseChar),
                "__NAME__" to name.uppercase(),
            )

        /** [settings] with [registration] after the `includeService` lines that follow [MARKER]. */
        fun register(
            settings: String,
            registration: String,
        ): String {
            val lines = settings.lines()
            val marker = lines.indexOfFirst { it.trim() == MARKER }
            if (marker < 0) refuse("settings.gradle.kts has no line '$MARKER'")
            var insertAt = marker + 1
            while (insertAt < lines.size && lines[insertAt].trimStart().startsWith("includeService(")) insertAt++
            return (lines.take(insertAt) + registration + lines.drop(insertAt)).joinToString("\n")
        }
    }
}
