// Cross-service Cucumber journeys in the ubiquitous language, run through the gateway of a Compose `core` stack.
// They need a running platform, so the suite executes only when GATEWAY_URL is set (CI platform workflow, quickstart);
// `verify` compiles it and skips it silently otherwise.
plugins {
    id("kotlin-library")
}

dependencies {
    testImplementation(libs.cucumber.java)
    testImplementation(libs.cucumber.junit.platform.engine)
    testImplementation(libs.junit.platform.suite)
    testImplementation(libs.jackson.module.kotlin)
    testImplementation(libs.awaitility)
}

tasks.named<Test>("test") {
    val gatewayUrl: Provider<String> = providers.environmentVariable("GATEWAY_URL")
    inputs.property("gatewayUrl", gatewayUrl).optional(true)
    onlyIf("GATEWAY_URL points at a running stack") { gatewayUrl.isPresent }
    systemProperty("cucumber.plugin", "summary")
}
