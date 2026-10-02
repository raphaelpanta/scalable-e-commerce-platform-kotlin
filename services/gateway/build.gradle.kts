// The single public entry point (contracts/gateway-routes.md): Spring Cloud Gateway in Kotlin with JWT validation,
// rate limiting, correlation-id sanitising and deny-by-default routing.
plugins {
    id("kotlin-boot-app")
}

dependencies {
    implementation(platform(libs.spring.cloud.bom))
    implementation(project(":libs:platform-core"))
    implementation(libs.spring.cloud.starter.gateway.server.webflux)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.spring.boot.starter.opentelemetry)

    testImplementation(testFixtures(project(":libs:platform-core")))
    testImplementation(libs.spring.boot.starter.security.test)
    testImplementation(libs.spring.security.oauth2.jose)
}
