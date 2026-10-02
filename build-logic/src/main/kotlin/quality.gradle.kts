import com.ecommerce.build.KtlintFindingsReport
import dev.detekt.gradle.Detekt
import dev.detekt.gradle.extensions.FailOnSeverity
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType
import org.jlleitschuh.gradle.ktlint.tasks.GenerateReportsTask

// Style and static analysis for every Kotlin module: ktlint (ktlint_official, .editorconfig) and detekt
// (config/detekt/detekt.yml on top of the defaults). Any finding fails the build (Principle I).
plugins {
    base
    id("org.jlleitschuh.gradle.ktlint")
    id("dev.detekt")
}

val catalog = the<VersionCatalogsExtension>().named("libs")

ktlint {
    version.set(catalog.findVersion("ktlint").get().requiredVersion)
    ignoreFailures.set(false)
    outputToConsole.set(false)
    coloredOutput.set(false)
    reporters {
        reporter(ReporterType.PLAIN)
    }
}

// ktlint's own Kotlin compiler prints a sun.misc.Unsafe warning on JDK 25 and the plugin does not expose
// the worker JVM arguments; the compiler embedded in the pinned Gradle version is warning-free.
configurations.matching { it.name == "ktlint" }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") useVersion(embeddedKotlinVersion)
    }
}

// Every ktlint report task (ktlint<SourceSet>SourceSetCheck, the task that fails on findings) gets its own
// finalizer that echoes only the report that task has just written, also when it failed. Echoing the whole
// reports directory from one shared finalizer printed reports left over from an earlier run.
afterEvaluate {
    tasks.withType<GenerateReportsTask>().names.toList().forEach { checkTask ->
        val findings =
            tasks.register<KtlintFindingsReport>("${checkTask}Findings") {
                reportsDirectory.set(layout.buildDirectory.dir("reports/ktlint/$checkTask"))
            }
        tasks.named(checkTask) { finalizedBy(findings) }
    }
}

detekt {
    toolVersion.set(catalog.findVersion("detekt").get().requiredVersion)
    buildUponDefaultConfig.set(true)
    config.setFrom(isolated.rootProject.projectDirectory.file("config/detekt/detekt.yml"))
    ignoreFailures.set(false)
    failOnSeverity.set(FailOnSeverity.Info)
}

tasks.withType<Detekt>().configureEach {
    reports {
        checkstyle.required.set(false)
        html.required.set(false)
        sarif.required.set(false)
        markdown.required.set(false)
    }
}

// `detekt` analyses sources without types; the per-source-set tasks (detektMain, detektTest, ...) add type
// resolution, which rules such as UnsafeCallOnNullableType need. check runs all of them.
tasks.named("check") {
    dependsOn("ktlintCheck", tasks.withType<Detekt>())
}
