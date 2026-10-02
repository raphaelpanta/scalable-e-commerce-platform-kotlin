plugins { id("kotlin-domain") }

pitest {
    mutationThreshold.set(79)
}
