// Convention for a service's `domain` module: pure Kotlin, unit tests only, mutation tested; it may not
// depend on any other project or on a framework (Principle II).
plugins {
    id("kotlin-base")
    id("pitest")
}

val forbiddenGroups = listOf("org.springframework", "io.r2dbc", "org.flywaydb", "jakarta")

afterEvaluate {
    listOf("api", "implementation")
        .mapNotNull { configurations.findByName(it) }
        .flatMap { it.dependencies }
        .forEach { dependency ->
            if (dependency is ProjectDependency) {
                throw GradleException("domain must not depend on ${dependency.path} (module $path)")
            }
            val group = dependency.group.orEmpty()
            if (forbiddenGroups.any { group.startsWith(it) }) {
                throw GradleException("domain must not depend on $group:${dependency.name} (module $path)")
            }
        }
}
