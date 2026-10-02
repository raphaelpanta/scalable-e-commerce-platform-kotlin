plugins { id("kotlin-service") }

dependencies {
    implementation(project(":libs:platform-core"))
    implementation(project(":libs:platform-messaging"))
    implementation(libs.spring.boot.starter.mail)

    testImplementation(testFixtures(project(":libs:platform-core")))
    testImplementation(testFixtures(project(":libs:platform-messaging")))
}
