plugins {
    id("kotlin-base")
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotest.assertions.core)
}
