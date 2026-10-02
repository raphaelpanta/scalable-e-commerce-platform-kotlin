rootProject.name = "boot-app-gateway"

// A standalone Boot application (the gateway, a Pact consumer) and a plain provider module verifying its pacts.
include(":services:gateway", ":services:echo")
