plugins {
    id("com.example.plugin") version "1.0.0"
}
dependencies { implementation("org.example:lib:1.2.3") }
detekt { toolVersion = "0.9.1" }
kotlin { jvmToolchain(25) }
