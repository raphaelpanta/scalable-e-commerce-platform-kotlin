plugins { id("kotlin-domain") }

dependencies {
    // Expected failures are values (Principle IV); Either is part of the domain API used by the outer layers.
    api(libs.arrow.core)
}
