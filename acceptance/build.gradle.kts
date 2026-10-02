// Cross-service Cucumber journeys in the ubiquitous language, run through the gateway of a Compose `core` stack.
// They need a running platform, so the suite executes only when GATEWAY_URL is set (CI platform workflow, quickstart);
// `verify` compiles it and skips it silently otherwise. Usage and environment: docs/acceptance.md.
plugins {
    id("kotlin-library")
}

dependencies {
    testImplementation(libs.cucumber.java)
    testImplementation(libs.cucumber.junit.platform.engine)
    testImplementation(libs.cucumber.picocontainer)
    testImplementation(libs.junit.platform.suite)
    testImplementation(libs.jackson.module.kotlin)
    testImplementation(libs.awaitility)
}

tasks.named<Test>("test") {
    val gatewayUrl: Provider<String> = providers.environmentVariable("GATEWAY_URL")
    // `-Dcucumber.filter.tags="@us4 and not @slow"` on the Gradle command line selects scenarios by tag.
    val tags: Provider<String> = providers.systemProperty("cucumber.filter.tags")
    inputs.property("gatewayUrl", gatewayUrl).optional(true)
    inputs.property("cucumberTags", tags).optional(true)
    onlyIf("GATEWAY_URL points at a running stack") { gatewayUrl.isPresent }
    // The outcome depends on the running stack, not only on the inputs: never reuse a previous result.
    outputs.upToDateWhen { false }
    systemProperty("cucumber.plugin", "summary")
    if (tags.isPresent) systemProperty("cucumber.filter.tags", tags.get())
}
