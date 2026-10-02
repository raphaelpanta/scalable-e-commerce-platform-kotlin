package com.ecommerce.build

import org.gradle.api.provider.Property

/** `docker { }` settings of the `docker-image` convention. */
interface DockerImageExtension {
    /** The Docker-API CLI to call; `docker` by default (tests substitute a stub). */
    val dockerExecutable: Property<String>
}
