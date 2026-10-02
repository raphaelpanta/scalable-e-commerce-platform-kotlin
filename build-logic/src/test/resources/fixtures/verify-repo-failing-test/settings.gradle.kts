rootProject.name = "verify-repo-failing-test"

// Stands in for the repository's build-logic included build, whose check the root verify runs.
includeBuild("build-logic")

include(":libs:alpha", ":libs:beta")
