plugins { id("kotlin-service") }

dependencies {
    implementation(project(":libs:platform-core"))
    implementation(project(":libs:platform-messaging"))
    implementation(libs.arrow.core)
    // JavaMailSender for the simulated SMS channel, mirrored to Mailpit like the notification service's
    implementation(libs.spring.boot.starter.mail)
    // Nimbus JOSE (JWT, JWK Set) and Spring Security's ReactiveJwtDecoder: identity signs and validates its own tokens
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    // Argon2id password hashing, and the Ed25519 public key of an imported private key
    implementation(libs.bcprov)

    testImplementation(testFixtures(project(":libs:platform-core")))
    testImplementation(testFixtures(project(":libs:platform-messaging")))
}
