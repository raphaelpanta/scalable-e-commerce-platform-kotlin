package com.ecommerce.architecture

import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Path

/**
 * Runs the shared [ArchitectureRules] against the service this infrastructure module belongs to. The
 * `kotlin-service` convention passes the service root and base package as system properties.
 */
class ArchitectureTest :
    FunSpec({
        test("domain and application respect the dependency rule") {
            val serviceRoot = Path.of(requireNotNull(System.getProperty("architecture.serviceRoot")))
            val basePackage = requireNotNull(System.getProperty("architecture.basePackage"))
            val violations = ArchitectureRules.check(serviceRoot, basePackage)
            if (violations.isNotEmpty()) {
                fail(violations.joinToString(separator = "\n"))
            }
        }
    })
