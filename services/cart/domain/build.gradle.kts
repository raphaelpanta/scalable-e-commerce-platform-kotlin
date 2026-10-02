plugins { id("kotlin-domain") }

dependencies {
    // Either and the raise DSL are part of the domain's public API (business errors as values).
    api(libs.arrow.core)
}
