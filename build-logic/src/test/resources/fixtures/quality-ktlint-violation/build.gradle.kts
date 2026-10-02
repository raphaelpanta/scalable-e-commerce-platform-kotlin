plugins {
    // ktlint only lints Kotlin source sets, so the fixture compiles Kotlin; kotlin-base adds this itself.
    id("org.jetbrains.kotlin.jvm")
    id("quality")
}
