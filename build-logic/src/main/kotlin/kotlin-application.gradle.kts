// Convention for a service's `application` module: use cases and ports in pure Kotlin with coroutines; it
// depends on its sibling `domain` only, never on adapters or Spring (Principle II).
plugins {
    id("kotlin-base")
    id("pitest")
}

val catalog = the<VersionCatalogsExtension>().named("libs")
val servicePath: String = requireNotNull(project.parent) { "$path must live inside a service directory" }.path
val domainPath = "$servicePath:domain"

dependencies {
    "implementation"(project(domainPath))
    "implementation"(catalog.findLibrary("kotlinx-coroutines-core").get())
    "testImplementation"(catalog.findLibrary("mockk").get())
}

afterEvaluate {
    listOf("api", "implementation")
        .mapNotNull { configurations.findByName(it) }
        .flatMap { it.dependencies }
        .forEach { dependency ->
            val offending =
                when {
                    dependency is ProjectDependency && dependency.path != domainPath -> {
                        dependency.path
                    }

                    dependency.group.orEmpty().startsWith("org.springframework") -> {
                        "${dependency.group}:${dependency.name}"
                    }

                    else -> {
                        null
                    }
                }
            if (offending != null) {
                throw GradleException("application must not depend on adapters ($offending) (module $path)")
            }
        }
}
