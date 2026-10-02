plugins { id("kotlin-service") }

dependencies {
    implementation(project(":libs:platform-core"))
    implementation(project(":libs:platform-messaging"))
    implementation(libs.arrow.core)
    // Boot's WebClient.Builder (codecs, observation) for the internal catalog client
    implementation(libs.spring.boot.starter.webclient)

    testImplementation(testFixtures(project(":libs:platform-core")))
    testImplementation(testFixtures(project(":libs:platform-messaging")))
}
