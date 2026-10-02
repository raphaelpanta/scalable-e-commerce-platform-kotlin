plugins { id("kotlin-application") }

dependencies {
    implementation(libs.arrow.core)
}

// Every resumption of a coroutine state machine calls kotlin.ResultKt.throwOnFailure; without the Arcmutate Kotlin
// plugin plain Pitest removes that call and reports an equivalent mutant per suspension point of the use cases.
pitest { avoidCallsTo.add("kotlin.ResultKt") }
