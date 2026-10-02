package com.ecommerce.architecture

import com.lemonappdev.konsist.api.Konsist
import java.nio.file.Files
import java.nio.file.Path

/**
 * The dependency rule (constitution Principle II) for every service, written once. The module graph is the
 * first guard (`kotlin-domain`, `kotlin-application`); these Konsist rules read the Kotlin sources of a
 * service's `domain` and `application` modules and report each forbidden import.
 */
object ArchitectureRules {
    private const val DOMAIN_RULE = "domain must not import frameworks"
    private const val APPLICATION_RULE = "application must not import adapters or frameworks"

    /** Returns one message per violation, `Rule '<rule>' violated by <file>: import <import>`; empty when clean. */
    fun check(
        serviceRoot: Path,
        basePackage: String,
    ): List<String> {
        val domainForbidden =
            listOf(
                "org.springframework",
                "io.r2dbc",
                "jakarta",
                "org.flywaydb",
                "$basePackage.application",
                "$basePackage.infrastructure",
            )
        val applicationForbidden = listOf("$basePackage.infrastructure", "org.springframework")
        return violations(serviceRoot.resolve("domain/src/main"), DOMAIN_RULE, domainForbidden) +
            violations(serviceRoot.resolve("application/src/main"), APPLICATION_RULE, applicationForbidden)
    }

    private fun violations(
        sources: Path,
        rule: String,
        forbiddenPrefixes: List<String>,
    ): List<String> {
        if (!Files.isDirectory(sources)) return emptyList()
        return Konsist
            .scopeFromExternalDirectory(sources.toAbsolutePath().toString())
            .files
            .flatMap { file ->
                file.imports
                    .map { it.name }
                    .filter { name -> forbiddenPrefixes.any { name == it || name.startsWith("$it.") } }
                    .map { name -> "Rule '$rule' violated by ${file.path}: import $name" }
            }.sorted()
    }
}
