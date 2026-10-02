package com.ecommerce.build

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import java.io.File

private val repoRoot: File = File(requireNotNull(System.getProperty("repo.root")))

private fun read(path: String): String = repoRoot.resolve(path).readText()

/** The lines indented below the top-level YAML key [key] (`key:` at column 0), trimmed. */
private fun topLevelBlock(
    yaml: String,
    key: String,
): List<String> =
    yaml
        .lines()
        .dropWhile { it != "$key:" }
        .drop(1)
        .takeWhile { it.isBlank() || it.startsWith(" ") }
        .map(String::trim)
        .filter(String::isNotEmpty)

class RepositoryDocsAndCiTest :
    FunSpec({
        test("docs/build.md has the documented sections and copy-pasteable commands") {
            val guide = read("docs/build.md")
            val headings = guide.lines().filter { it.startsWith("## ") }

            headings shouldContainAll
                listOf(
                    "## Layout",
                    "## Verify command",
                    "## Running a test layer",
                    "## Adding a module",
                    "## Adding a dependency",
                    "## Quality gates",
                )
            listOf(
                "./gradlew -q verify",
                "./gradlew -q :services:catalog:infrastructure:integrationTest",
                "./gradlew newService -Pname=",
                "gradle/libs.versions.toml",
            ).forEach { guide shouldContain it }
        }

        test("README links to the build guide and the frontend location is reserved") {
            read("README.md") shouldContain "(docs/build.md)"
            repoRoot.resolve("frontend/README.md").shouldExist()
        }

        test("the verify workflow runs the gate on pushes to main and for pr-gate, read-only and bounded") {
            val workflow = read(".github/workflows/verify.yml")
            val triggers = topLevelBlock(workflow, "on")

            // Pull requests reach verify through .github/workflows/pr-gate.yml (workflow_call), never twice.
            triggers.take(3) shouldBe listOf("push:", "branches: [main]", "workflow_call:")
            triggers.none { it.startsWith("pull_request") } shouldBe true
            read(".github/workflows/pr-gate.yml") shouldContain "uses: ./.github/workflows/verify.yml"
            topLevelBlock(workflow, "permissions") shouldBe listOf("contents: read")
            workflow.lines().count { it.trim() == "permissions:" || it.trim().startsWith("permissions: ") } shouldBe 1
            workflow shouldContain "timeout-minutes: 15"
            workflow.lines().map(String::trim) shouldContainAll listOf("run: ./gradlew -q verify")
            workflow shouldContain "github.event.pull_request.head.repo.full_name == github.repository"
        }

        test("every action in the workflow is pinned to a full commit SHA") {
            val references =
                Regex("""(?m)^\s*(?:-\s+)?uses:\s*(\S+)""")
                    .findAll(read(".github/workflows/verify.yml"))
                    .map { it.groupValues[1] }
                    .toList()

            references.shouldNotBeEmpty()
            references.forEach { it shouldMatch Regex("""[\w.-]+/[\w./-]+@[0-9a-f]{40}""") }
        }
    })
