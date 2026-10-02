plugins { id("kotlin-application") }

dependencies {
    implementation(libs.arrow.core)
}

// Every suspending use case resumes through kotlin.ResultKt.throwOnFailure, compiler-generated coroutine machinery
// like the kotlinx.coroutines calls the convention already leaves alone; mutating it only yields equivalent mutants.
pitest {
    avoidCallsTo.add("kotlin.ResultKt")
}
