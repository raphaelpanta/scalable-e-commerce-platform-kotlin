plugins {
    id("repository-root")
}

monorepo {
    exempt(":libs:naked", "generated code, no checks wanted")
}
