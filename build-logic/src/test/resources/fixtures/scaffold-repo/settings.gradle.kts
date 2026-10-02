rootProject.name = "scaffold-repo"

fun includeService(name: String) {
    include(":services:$name:domain", ":services:$name:application", ":services:$name:infrastructure")
}

// --- includeService registry (the newService task appends below this line) ---
includeService("demo")
