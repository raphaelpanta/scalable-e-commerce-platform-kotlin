import com.ecommerce.build.PROVIDER_TAG
import com.ecommerce.build.PactBrokerArguments
import com.ecommerce.build.contractTestTasksOfTheBuild
import com.ecommerce.build.pactFolder
import com.ecommerce.build.registerTestLayer

// Contract layer (Pact JVM), split in two tasks over the same `src/contractTest` classes:
//  - `contractTest` runs the consumer tests (everything except JUnit tag `provider`); they write pact files to
//    the repository root `build/pacts` (system property `pact.rootDir`), shared by every module of the build.
//  - `contractVerify` runs only the provider verification classes (tag `provider`) after every `contractTest`
//    task of the build; they read the pacts with @PactFolder("${pact.folder}") and @IgnoreNoPactsToVerify.
// With the environment variable PACT_BROKER_URL both tasks also get `pactbroker.url`, the optional credentials
// (PACT_BROKER_TOKEN, or PACT_BROKER_USERNAME and PACT_BROKER_PASSWORD), `pact.provider.version` (GITHUB_SHA
// or `git rev-parse HEAD`), the provider branch (PACT_PROVIDER_BRANCH) and, for a run a broker webhook triggered, the
// pact to verify (PACT_URL, PACT_CONSUMER); verification results are published only when PACT_PUBLISH_RESULTS=true.
val catalog = the<VersionCatalogsExtension>().named("libs")
val pactDirectory: File =
    isolated.rootProject.projectDirectory
        .dir("build/pacts")
        .asFile

val contractTest = registerTestLayer("contractTest")

dependencies {
    "contractTestImplementation"(catalog.findLibrary("pact-consumer-junit5").get())
    "contractTestImplementation"(catalog.findLibrary("pact-provider-junit5").get())
}

val pactBrokerUrl: Provider<String> = providers.environmentVariable("PACT_BROKER_URL").filter(String::isNotBlank)
val gitSha: Provider<String> =
    providers
        .environmentVariable("GITHUB_SHA")
        .orElse(
            providers
                .exec {
                    commandLine("git", "rev-parse", "HEAD")
                    workingDir(isolated.rootProject.projectDirectory.asFile)
                    isIgnoreExitValue = true
                }.standardOutput.asText
                .map(String::trim),
        ).filter(String::isNotBlank)

/** A non-blank environment variable, read only when a broker is configured. */
fun brokerOnly(name: String): Provider<String> =
    pactBrokerUrl.flatMap { providers.environmentVariable(name) }.filter(String::isNotBlank)

fun brokerArguments(): PactBrokerArguments =
    objects.newInstance<PactBrokerArguments>().apply {
        brokerUrl.set(pactBrokerUrl)
        token.set(providers.environmentVariable("PACT_BROKER_TOKEN"))
        username.set(providers.environmentVariable("PACT_BROKER_USERNAME"))
        password.set(providers.environmentVariable("PACT_BROKER_PASSWORD"))
        // Only resolved (and git only run) when a broker is configured.
        providerVersion.set(pactBrokerUrl.flatMap { gitSha })
        providerBranch.set(brokerOnly("PACT_PROVIDER_BRANCH"))
        pactUrl.set(brokerOnly("PACT_URL"))
        consumer.set(brokerOnly("PACT_CONSUMER"))
        publishResults.set(providers.environmentVariable("PACT_PUBLISH_RESULTS").map { it == "true" }.orElse(false))
    }

contractTest.configure {
    targets.configureEach {
        testTask.configure {
            val pacts = pactDirectory
            pactFolder(pacts)
            jvmArgumentProviders.add(brokerArguments())
            useJUnitPlatform { excludeTags(PROVIDER_TAG) }
            // The pact files are written outside the task's outputs: a cache hit would restore the test reports
            // without the pacts, so the consumer tests always run when they are not up to date.
            outputs.cacheIf("pact files are written outside the task outputs") { false }
            outputs.upToDateWhen { pacts.isDirectory }
        }
    }
}

val contractSources: SourceSet = the<SourceSetContainer>()["contractTest"]

val contractVerify =
    tasks.register<Test>("contractVerify") {
        val pacts = pactDirectory
        group = "verification"
        description = "Verifies this module's Pact providers against the pacts in the repository root build/pacts"
        testClassesDirs = contractSources.output.classesDirs
        classpath = contractSources.runtimeClasspath
        pactFolder(pacts)
        jvmArgumentProviders.add(brokerArguments())
        useJUnitPlatform {
            includeEngines("junit-jupiter")
            includeTags(PROVIDER_TAG)
        }
        inputs
            .files(fileTree(pacts))
            .withPropertyName("pacts")
            .withPathSensitivity(PathSensitivity.RELATIVE)
        // Pacts fetched from a broker are not inputs: with a broker, verification always runs.
        val brokerless = pactBrokerUrl.map { false }.orElse(true)
        outputs.cacheIf("no pact broker is configured") { brokerless.get() }
        outputs.upToDateWhen { brokerless.get() }
        mustRunAfter(project.contractTestTasksOfTheBuild())
    }

tasks.named("check") {
    dependsOn(contractVerify)
}
