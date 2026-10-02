package com.ecommerce.build

import org.gradle.api.provider.Property

/**
 * `mutation { }` settings of the `pitest` convention. [targetPackage] is the package whose classes are mutated
 * (`<targetPackage>.*`); service modules (`:services:<ctx>:<layer>`) get `com.ecommerce.<ctx>.<layer>` by
 * convention, any other module sets it, for example `mutation { targetPackage.set("com.ecommerce.platform") }`.
 * `-Pharness.mutation.classes=<glob>[,<glob>...]` still narrows the run when given.
 */
interface MutationExtension {
    val targetPackage: Property<String>
}

/** `:services:<ctx>:<layer>` -> `com.ecommerce.<ctx>.<layer>`; `null` for a module outside `services/`. */
fun servicePackage(path: String): String? =
    path
        .takeIf { it.startsWith(":services:") }
        ?.removePrefix(":services:")
        ?.split(':')
        ?.joinToString(separator = ".", prefix = "com.ecommerce.")
