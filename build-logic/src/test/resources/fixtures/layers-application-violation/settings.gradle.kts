rootProject.name = "layers-application-violation"

fun includeService(name: String) {
    include(":services:$name:domain", ":services:$name:application", ":services:$name:infrastructure")
}

includeService("demo")
