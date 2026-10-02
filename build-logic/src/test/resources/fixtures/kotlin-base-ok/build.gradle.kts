plugins {
    id("kotlin-base")
}

tasks.register("printToolchain") {
    val toolchainLanguage = java.toolchain.languageVersion
    val warningsAsErrors = kotlin.compilerOptions.allWarningsAsErrors
    val archives = base.archivesName
    doLast {
        println("toolchain=${toolchainLanguage.get()}")
        println("allWarningsAsErrors=${warningsAsErrors.get()}")
        println("archivesName=${archives.get()}")
    }
}
