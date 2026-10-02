package com.ecommerce.build

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import java.io.File

private val ROOTS =
    listOf("domain", "application", "infrastructure").flatMap { module ->
        listOf("$module/src/main", "$module/src/test")
    } + listOf("integrationTest", "contractTest", "acceptanceTest").map { "infrastructure/src/$it" }

/** Directories below the compared source roots, relative to [service], with the service name replaced by NAME. */
private fun layout(
    service: File,
    name: String,
): List<String> =
    ROOTS
        .map(service::resolve)
        .filter(File::isDirectory)
        .flatMap { root -> root.walkTopDown().filter(File::isDirectory).toList() }
        .map { it.relativeTo(service).invariantSeparatorsPath.replace(name, "NAME") }
        .sorted()

private fun files(
    service: File,
    name: String,
): List<String> =
    service
        .walkTopDown()
        .filter { it.isFile && "/build/" !in it.invariantSeparatorsPath }
        .map { it.relativeTo(service).invariantSeparatorsPath }
        .map { it.replace(name, "NAME").replace(name.capitalised(), "Name") }
        .toList()

private fun String.capitalised(): String = replaceFirstChar(Char::uppercaseChar)

class ServiceLayoutParityTest :
    FunSpec({
        // Feature 004 grew the catalogue in place (persistence, web, messaging, ... packages and db/seed), so the
        // reference service now holds the scaffolded layout plus its own packages.
        test("a scaffolded service has the directory layout of services/catalog (AC 4.3)") {
            val catalog = File(requireNotNull(System.getProperty("repo.root"))).resolve("services/catalog")
            val fixture = FixtureProject.prepare("scaffold-repo")
            fixture.run("newService", "-Pname=orders")
            val orders = fixture.file("services/orders")

            layout(catalog, "catalog") shouldContainAll layout(orders, "orders")
            val essentials =
                listOf(
                    "domain/build.gradle.kts",
                    "application/build.gradle.kts",
                    "infrastructure/build.gradle.kts",
                    "infrastructure/src/main/resources/application.yml",
                    "infrastructure/src/main/resources/db/migration/V1__baseline.sql",
                    "infrastructure/src/main/kotlin/com/ecommerce/NAME/infrastructure/NameApplication.kt",
                )
            files(catalog, "catalog") shouldContainAll essentials
            files(orders, "orders") shouldContainAll essentials
        }
    })
