// The single public entry point (contracts/gateway-routes.md): Spring Cloud Gateway in Kotlin with JWT validation,
// rate limiting, correlation-id sanitising and deny-by-default routing.
plugins {
    id("kotlin-boot-app")
    id("pitest")
}

// Mutation testing (T146, Principle VIII): the unit layer against com.ecommerce.gateway.* (the convention's
// default for :services:gateway, named here because the module has no domain/application layers).
mutation {
    targetPackage.set("com.ecommerce.gateway")
}

pitest {
    // Pitest runs the unit layer only. These classes are Spring wiring or I/O adapters without logic of their own
    // that a unit test could pin down; their behaviour is covered by the integration and contract layers instead:
    // - GatewayApplication: the Boot entry point;
    // - SecurityConfiguration: the Spring Security DSL (GatewayRoutingIT: 401/403/503 answers);
    // - OpenTelemetryAppenderInstaller: hands the OpenTelemetry bean to the Logback appender;
    // - JwksClient: WebClient fetch and Reactor cache of the JWK Set (JwksClientIT, JwksUnavailableIT against
    //   WireMock; IdentityJwksPactTest against the identity contract).
    excludedClasses.addAll(
        "com.ecommerce.gateway.GatewayApplication*",
        "com.ecommerce.gateway.security.SecurityConfiguration*",
        "com.ecommerce.gateway.observability.OpenTelemetryAppenderInstaller*",
        "com.ecommerce.gateway.security.JwksClient*",
    )
}

dependencies {
    implementation(platform(libs.spring.cloud.bom))
    implementation(libs.spring.cloud.starter.gateway.server.webflux)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.spring.boot.starter.opentelemetry)
    // Logback events as OpenTelemetry log records for Loki (logback-spring.xml, OpenTelemetryAppenderInstaller)
    implementation(libs.opentelemetry.logback.appender)

    testImplementation(libs.spring.boot.starter.security.test)
    testImplementation(libs.spring.security.oauth2.jose)
}
