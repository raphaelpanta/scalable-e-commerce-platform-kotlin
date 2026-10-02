import com.ecommerce.build.QualityThresholds
import info.solidsoft.gradle.pitest.PitestPluginExtension

// Mutation testing for domain and application modules (Principle VIII). Pitest with the JUnit 5 plugin and
// exclusions for Kotlin-synthetic code (the default). Two project properties (specs/001-harness-quality-gates):
//  - harness.mutation.classes=<glob>[,<glob>...] narrows targetClasses; the Stop hook passes the classes of the
//    changed files (incremental mutation). Without it every class of the module is mutated (CI, verify).
//  - harness.pitest.arcmutate=true adds the commercial Arcmutate Kotlin plugin; it needs an Arcmutate licence
//    file and stays false in gradle.properties (research.md, "Arcmutate licence outcome").
plugins {
    id("info.solidsoft.pitest")
}

val catalog = the<VersionCatalogsExtension>().named("libs")

// :services:<service>:<layer> -> com.ecommerce.<service>.<layer>
val targetPackage: String =
    path
        .removePrefix(":services:")
        .split(':')
        .joinToString(separator = ".", prefix = "com.ecommerce.")

/** "a.B*, c.D*" -> {"a.B*", "c.D*"}: the value of -Pharness.mutation.classes. */
fun classGlobs(property: String): Set<String> =
    property
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toSet()

val pitest = the<PitestPluginExtension>()

pitest.apply {
    pitestVersion.set(catalog.findVersion("pitest").get().requiredVersion)
    junit5PluginVersion.set(catalog.findVersion("pitest-junit5-plugin").get().requiredVersion)
    targetClasses.set(
        providers
            .gradleProperty("harness.mutation.classes")
            .map(::classGlobs)
            .orElse(setOf("$targetPackage.*")),
    )
    mutationThreshold.convention(QualityThresholds.MINIMUM_MUTATION_THRESHOLD)
    threads.set(Runtime.getRuntime().availableProcessors())
    outputFormats.set(setOf("XML", "HTML"))
    timestampedReports.set(false)
    verbose.set(false)
    verbosity.set("QUIET")
    failWhenNoMutations.set(false)
    // Pitest 1.30 delegates history (incremental analysis) to a plugin; without the Arcmutate history plugin
    // enabling it aborts the run, so every run is a full analysis until that follow-up lands.
    enableDefaultIncrementalAnalysis.set(false)
    excludedClasses.set(setOf("*\$WhenMappings", "*\$DefaultImpls", "*Kt\$*\$1"))
    avoidCallsTo.set(setOf("kotlin.jvm.internal", "kotlin.Intrinsics", "kotlinx.coroutines"))
    excludedMethods.set(setOf("toString", "hashCode", "equals", "copy", "component*"))
}

if (providers.gradleProperty("harness.pitest.arcmutate").orNull.toBoolean()) {
    dependencies {
        "pitest"(catalog.findLibrary("arcmutate-pitest-kotlin-plugin").get())
    }
}

afterEvaluate {
    val threshold = pitest.mutationThreshold.get()
    if (threshold < QualityThresholds.MINIMUM_MUTATION_THRESHOLD) {
        throw GradleException(
            "Mutation threshold $threshold is below the constitution minimum " +
                "${QualityThresholds.MINIMUM_MUTATION_THRESHOLD} (Principle VIII)",
        )
    }
}

tasks.named<JavaExec>("pitest") {
    // Pitest always prints its mutator table, statistics and an INFO banner. A passing run must be silent
    // (Principle VIII), so stdout is dropped and stderr is kept in build/pitest/stderr.log; on failure the
    // stderr text (for example "Mutation score of 50 is below threshold of 80") becomes the build failure.
    // Streams are swapped at execution time: the configuration cache only accepts System.out/System.err.
    val stderrLog: File =
        layout.buildDirectory
            .file("pitest/stderr.log")
            .get()
            .asFile
    val unitTestSources: FileCollection = project.the<SourceSetContainer>()["test"].allSource
    onlyIf("the module has unit tests") { !unitTestSources.isEmpty }
    isIgnoreExitValue = true
    doFirst {
        stderrLog.parentFile.mkdirs()
        (this as JavaExec).standardOutput = java.io.OutputStream.nullOutputStream()
        errorOutput = stderrLog.outputStream()
    }
    doLast {
        val pitestRun = this as JavaExec
        pitestRun.errorOutput.close()
        val exitValue = pitestRun.executionResult.get().exitValue
        if (exitValue != 0) {
            val details =
                stderrLog
                    .readLines()
                    .filterNot { it.contains("PIT >> INFO") || it.trimStart().startsWith("at ") }
                    .joinToString(separator = "\n")
            throw GradleException("Pitest failed (exit $exitValue); report in build/reports/pitest\n$details")
        }
    }
}

tasks.named("check") {
    dependsOn("pitest")
}
