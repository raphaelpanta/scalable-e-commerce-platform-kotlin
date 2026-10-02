package com.ecommerce.build

import org.gradle.api.GradleException
import org.gradle.api.Project

/** The marker every named convention (kotlin-domain, kotlin-application, kotlin-service, ...) applies. */
private const val CONVENTION_MARKER = "kotlin-base"

/**
 * Fails when a leaf module applies none of the named conventions and is not exempted (FR-013). Group
 * projects (projects with children, such as `:services` or `:services:catalog`) and the root are ignored.
 * Call on the root project once every project is evaluated.
 */
fun Project.verifyConventions(exemptions: Map<String, String>) {
    val offenders =
        rootProject.allprojects
            .filter { it != rootProject && it.childProjects.isEmpty() }
            .filterNot { it.pluginManager.hasPlugin(CONVENTION_MARKER) || it.path in exemptions }
            .map { it.path }
            .sorted()
    if (offenders.isNotEmpty()) {
        throw GradleException(
            offenders.joinToString(separator = "\n") { path ->
                "Module $path applies no convention; apply a convention (one of kotlin-domain, kotlin-application, " +
                    "kotlin-service, kotlin-boot-app, kotlin-library) or exempt it with " +
                    "monorepo { exempt(\"$path\", \"<reason>\") }"
            },
        )
    }
}
