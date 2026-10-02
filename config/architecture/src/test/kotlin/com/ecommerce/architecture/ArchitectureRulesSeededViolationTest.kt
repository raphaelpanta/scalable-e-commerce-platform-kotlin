package com.ecommerce.architecture

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import java.io.File

private const val BASE_PACKAGE = "com.ecommerce.catalog"

private fun File.writeSource(
    layer: String,
    vararg lines: String,
): File {
    val file = resolve("$layer/src/main/kotlin/Bad.kt")
    file.parentFile.mkdirs()
    file.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
    return file
}

class ArchitectureRulesSeededViolationTest :
    FunSpec({
        test("a framework import in domain and an adapter import in application name the rule and the file") {
            val serviceRoot = tempdir()
            val domainFile =
                serviceRoot.writeSource(
                    "domain",
                    "package com.ecommerce.catalog.domain",
                    "",
                    "import org.springframework.stereotype.Component",
                    "",
                    "@Component",
                    "class Bad",
                )
            val applicationFile =
                serviceRoot.writeSource(
                    "application",
                    "package com.ecommerce.catalog.application",
                    "",
                    "import com.ecommerce.catalog.infrastructure.CatalogApplication",
                    "",
                    "class Bad(val app: CatalogApplication)",
                )

            ArchitectureRules.check(serviceRoot.toPath(), BASE_PACKAGE) shouldContainExactlyInAnyOrder
                listOf(
                    "Rule 'domain must not import frameworks' violated by ${domainFile.path}: " +
                        "import org.springframework.stereotype.Component",
                    "Rule 'application must not import adapters or frameworks' violated by ${applicationFile.path}: " +
                        "import com.ecommerce.catalog.infrastructure.CatalogApplication",
                )
        }

        test("every forbidden prefix in domain is reported") {
            val serviceRoot = tempdir()
            val forbidden =
                listOf(
                    "org.springframework.context.ApplicationContext",
                    "io.r2dbc.spi.ConnectionFactory",
                    "jakarta.inject.Inject",
                    "org.flywaydb.core.Flyway",
                    "com.ecommerce.catalog.application.CheckServiceHealth",
                    "com.ecommerce.catalog.infrastructure.CatalogApplication",
                )
            val file =
                serviceRoot.writeSource(
                    "domain",
                    "package com.ecommerce.catalog.domain",
                    "",
                    *forbidden.map { "import $it" }.toTypedArray(),
                )

            ArchitectureRules.check(serviceRoot.toPath(), BASE_PACKAGE) shouldContainExactlyInAnyOrder
                forbidden.map { "Rule 'domain must not import frameworks' violated by ${file.path}: import $it" }
        }

        test("a clean service tree has no violations") {
            val serviceRoot = tempdir()
            serviceRoot.writeSource(
                "domain",
                "package com.ecommerce.catalog.domain",
                "",
                "import kotlin.math.max",
                "",
                "fun bigger(a: Int, b: Int) = max(a, b)",
            )
            serviceRoot.writeSource(
                "application",
                "package com.ecommerce.catalog.application",
                "",
                "import com.ecommerce.catalog.domain.bigger",
                "import kotlinx.coroutines.yield",
                "",
                "suspend fun biggest(a: Int, b: Int): Int {",
                "    yield()",
                "    return bigger(a, b)",
                "}",
            )

            ArchitectureRules.check(serviceRoot.toPath(), BASE_PACKAGE).shouldBeEmpty()
        }

        test("a service without application or domain sources has no violations") {
            ArchitectureRules.check(tempdir().toPath(), BASE_PACKAGE).shouldBeEmpty()
        }
    })
