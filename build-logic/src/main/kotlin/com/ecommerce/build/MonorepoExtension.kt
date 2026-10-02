package com.ecommerce.build

import org.gradle.api.provider.MapProperty

/** Root-level `monorepo { }` settings exposed by the `repository-root` convention. */
abstract class MonorepoExtension {
    /** Module path to the reason it may skip the conventions (FR-013). */
    abstract val exemptions: MapProperty<String, String>

    /** Exempts the module at [path] from the convention guard; [reason] is mandatory documentation. */
    fun exempt(
        path: String,
        reason: String,
    ) {
        require(reason.isNotBlank()) { "an exemption for $path needs a reason" }
        exemptions.put(path, reason)
    }
}
