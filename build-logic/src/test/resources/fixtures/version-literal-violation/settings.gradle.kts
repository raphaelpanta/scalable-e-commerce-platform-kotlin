rootProject.name = "version-literal-violation"

// libs/bad is deliberately NOT included: its script could not even be configured. checkVersionLiterals
// scans every *.gradle.kts file in the tree, included or not.
