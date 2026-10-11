import com.ecommerce.build.MutationExtension
import com.ecommerce.build.QualityThresholds
import com.ecommerce.build.heavyTaskLimit
import com.ecommerce.build.servicePackage
import info.solidsoft.gradle.pitest.PitestPluginExtension

// Mutation testing (Principle VIII). Pitest with the JUnit 5 plugin and exclusions for Kotlin-synthetic code (the
// default). Target classes, the first setting that applies:
//  1. -Pharness.mutation.classes=<glob>[,<glob>...] narrows the run; the Stop hook passes the classes of the
//     changed files (incremental mutation). It always wins.
//  2. mutation { targetPackage.set("com.ecommerce.platform") } in the module's build script mutates
//     `<targetPackage>.*`; modules outside services/ (shared libraries) must set it.
//  3. The default of a service module :services:<ctx>:<layer> is com.ecommerce.<ctx>.<layer>.*.
// harness.pitest.arcmutate=true adds the commercial Arcmutate Kotlin plugin; it needs an Arcmutate licence file and
// stays false in gradle.properties (specs/001-harness-quality-gates research.md, "Arcmutate licence outcome").
plugins {
    id("info.solidsoft.pitest")
}

val catalog = the<VersionCatalogsExtension>().named("libs")

val mutation = extensions.create<MutationExtension>("mutation")
servicePackage(path)?.let { mutation.targetPackage.convention(it) }

/** "a.B*, c.D*" -> {"a.B*", "c.D*"}: the value of -Pharness.mutation.classes. */
fun classGlobs(property: String): Set<String> =
    property
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toSet()

val harnessClasses: Provider<Set<String>> = providers.gradleProperty("harness.mutation.classes").map(::classGlobs)

val pitest = the<PitestPluginExtension>()

pitest.apply {
    pitestVersion.set(catalog.findVersion("pitest").get().requiredVersion)
    junit5PluginVersion.set(catalog.findVersion("pitest-junit5-plugin").get().requiredVersion)
    targetClasses.set(harnessClasses.orElse(mutation.targetPackage.map { setOf("$it.*") }))
    mutationThreshold.convention(QualityThresholds.MINIMUM_MUTATION_THRESHOLD)
    // Pitest forks one minion JVM per thread (about 300 MB each), outside Gradle's worker limit, and up to
    // org.gradle.workers.max modules run it at once. All CPUs per task oversubscribed the machine several times over
    // (load average 37 on 10 CPUs on the CI runner, with Testcontainers timing out next to it); each task now gets
    // twice its share of the CPUs, which still keeps a lone Pitest run busy.
    val cpus = Runtime.getRuntime().availableProcessors()
    val workers = gradle.startParameter.maxWorkerCount.coerceAtLeast(1)
    threads.set(((2 * cpus + workers - 1) / workers).coerceIn(1, cpus))
    // Explicit heaps: a JVM without -Xmx takes a quarter of the container's memory as its ceiling, and the
    // Pitest JVMs of several modules next to the test JVMs and the Gradle and Kotlin daemons then outgrew the
    // CI runner's cap (the kernel killed the Gradle daemon). Unit tests of one module need far less.
    jvmArgs.set(listOf("-Xmx512m"))
    mainProcessJvmArgs.set(listOf("-Xmx768m"))
    outputFormats.set(setOf("XML", "HTML"))
    timestampedReports.set(false)
    verbose.set(false)
    verbosity.set("QUIET")
    failWhenNoMutations.set(false)
    // Pitest 1.30 delegates history (incremental analysis) to a plugin; without the Arcmutate history plugin
    // enabling it aborts the run, so every run is a full analysis until that follow-up lands.
    enableDefaultIncrementalAnalysis.set(false)
    excludedClasses.set(setOf("*\$WhenMappings", "*\$DefaultImpls", "*Kt\$*\$1"))
    // kotlin.ResultKt: coroutine resume paths (Result.throwOnFailure) yield mutants no test can kill
    avoidCallsTo.set(setOf("kotlin.jvm.internal", "kotlin.Intrinsics", "kotlinx.coroutines", "kotlin.ResultKt"))
    excludedMethods.set(setOf("toString", "hashCode", "equals", "copy", "component*"))
}

if (providers.gradleProperty("harness.pitest.arcmutate").orNull.toBoolean()) {
    dependencies {
        "pitest"(catalog.findLibrary("arcmutate-pitest-kotlin-plugin").get())
    }
}

afterEvaluate {
    if (!mutation.targetPackage.isPresent && !harnessClasses.isPresent) {
        throw GradleException(
            "Module $path applies pitest outside services/; set the package to mutate with " +
                "mutation { targetPackage.set(\"com.ecommerce.<package>\") }",
        )
    }
    val threshold = pitest.mutationThreshold.get()
    if (threshold < QualityThresholds.MINIMUM_MUTATION_THRESHOLD) {
        throw GradleException(
            "Mutation threshold $threshold is below the constitution minimum " +
                "${QualityThresholds.MINIMUM_MUTATION_THRESHOLD} (Principle VIII)",
        )
    }
}

tasks.named<JavaExec>("pitest") {
    usesService(heavyTaskLimit())
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
