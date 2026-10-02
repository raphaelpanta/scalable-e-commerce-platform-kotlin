rootProject.name = "layers-boundaries"

fun includeService(name: String) {
    include(":services:$name:domain", ":services:$name:application", ":services:$name:infrastructure")
}

includeService("demo")
