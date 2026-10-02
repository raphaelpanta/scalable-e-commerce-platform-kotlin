plugins { id("kotlin-library") }

// Versionless: the Spring Boot BOM of the kotlin-library convention supplies the version.
dependencies {
    implementation(libs.spring.boot.starter.webflux)
}
