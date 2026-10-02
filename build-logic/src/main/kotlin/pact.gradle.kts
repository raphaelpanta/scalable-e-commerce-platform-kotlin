import com.ecommerce.build.registerTestLayer

// Contract layer (Pact JVM consumer and provider tests) for service modules: `contractTest` suite, pacts
// written to build/pacts, consumer test classes ordered before provider verification with @Order.
val catalog = the<VersionCatalogsExtension>().named("libs")
val pactDirectory: String =
    layout.buildDirectory
        .dir("pacts")
        .get()
        .asFile.path

val contractTest = registerTestLayer("contractTest")

dependencies {
    "contractTestImplementation"(catalog.findLibrary("pact-consumer-junit5").get())
    "contractTestImplementation"(catalog.findLibrary("pact-provider-junit5").get())
}

contractTest.configure {
    targets.configureEach {
        testTask.configure {
            systemProperty("pact.rootDir", pactDirectory)
            systemProperty("pact.writer.overwrite", "true")
            systemProperty(
                "junit.jupiter.testclass.order.default",
                "org.junit.jupiter.api.ClassOrderer\$OrderAnnotation",
            )
            systemProperty("pact.verifier.publishResults", "false")
        }
    }
}
