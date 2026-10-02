package com.ecommerce.build

import java.io.File

/** One version literal found in a build script. */
data class VersionLiteral(
    val path: String,
    val line: Int,
    val text: String,
) {
    override fun toString(): String =
        "$path:$line: version literal '$text'; declare it in gradle/libs.versions.toml and reference it " +
            "through the catalogue"
}

/**
 * Finds version literals in Gradle Kotlin DSL scripts (research.md section 3): quoted Maven coordinates
 * with a version, plugin versions, tool version assignments and toolchain numbers.
 */
object VersionLiterals {
    /** Ant patterns (relative to the repository root) that are never scanned. */
    val EXCLUDES: List<String> =
        listOf(
            "**/build/**",
            "**/.gradle/**",
            "**/node_modules/**",
            "**/.git/**",
            // Agent worktrees (nested checkouts) are not part of this build.
            ".claude/worktrees/**",
            "build-logic/src/test/resources/fixtures/**",
        )

    private val excludedDirectoryNames = setOf("build", ".gradle", "node_modules", ".git", "worktrees")
    private const val FIXTURES = "build-logic/src/test/resources/fixtures"

    private val patterns: List<Pair<Regex, (MatchResult) -> String>> =
        listOf(
            Regex("\"([\\w.\\-]+:[\\w.\\-]+:[\\w.\\-+]*\\d[\\w.\\-+]*)\"") to { match -> match.groupValues[1] },
            Regex("(?:id|kotlin)\\(\"[^\"]+\"\\)\\s+version\\s+\"[^\"]+\"") to { match -> match.value },
            Regex("\\w*[Vv]ersion\\s*(?:=|\\.set\\()\\s*\"?[^\"\\s)]*\\d[^\"\\s)]*\"?\\)?") to { match -> match.value },
            Regex("(?:JavaLanguageVersion\\.of|jvmToolchain)\\(\\s*\\d+\\s*\\)") to { match -> match.value },
        )

    /** Literals in one script; [path] is how the file is named in the report. */
    fun find(
        path: String,
        content: String,
    ): List<VersionLiteral> =
        content.lines().flatMapIndexed { index, line ->
            patterns.flatMap { (regex, text) ->
                regex.findAll(line).map { VersionLiteral(path, index + 1, text(it)) }.toList()
            }
        }

    /** Literals in the given scripts, reported relative to [root]. */
    fun find(
        root: File,
        scripts: Iterable<File>,
    ): List<VersionLiteral> =
        scripts
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
            .flatMap { find(it.relativeTo(root).invariantSeparatorsPath, it.readText()) }

    /** Walks [root] the way `checkVersionLiterals` does and returns every literal found. */
    fun scan(root: File): List<VersionLiteral> {
        val scripts =
            root
                .walkTopDown()
                .onEnter { dir ->
                    dir.name !in excludedDirectoryNames && dir.relativeTo(root).invariantSeparatorsPath != FIXTURES
                }.filter { it.isFile && it.name.endsWith(".gradle.kts") }
                .toList()
        return find(root, scripts)
    }
}
