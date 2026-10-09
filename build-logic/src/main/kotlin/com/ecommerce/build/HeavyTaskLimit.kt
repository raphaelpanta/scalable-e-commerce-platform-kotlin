package com.ecommerce.build

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import java.lang.management.ManagementFactory

/** Memory the Gradle daemon, the Kotlin daemon and a Node process take next to the heavy tasks, in GiB. */
private const val RESERVED_GIB = 6

/** Heavy tasks per GiB left over: a test or Pitest JVM (1 GB heap, see kotlin-base and pitest) uses about 1.3 GB. */
private const val SLOTS_PER_FOUR_GIB = 3
private const val GIB_PER_SLOT_GROUP = 4

/** Never fewer: one module's tests next to another's compile and Pitest still overlap. */
private const val MINIMUM_SLOTS = 2

private const val BYTES_PER_GIB = 1L shl 30

/**
 * Bounds how many memory-heavy tasks run at once: test JVMs, Pitest and the frontend's npm scripts (each 1 to 1.5 GB
 * resident), while compilation and the other tasks keep every Gradle worker. On the CI runner, a container capped at
 * 10 GB, six heavy tasks next to the Gradle daemon (2.8 GB), the Kotlin daemon (1.6 GB) and ESLint (1.5 GB) reached
 * the cap and the kernel killed the Gradle daemon. The number of slots follows the memory the build sees (the
 * container's limit inside a container): three under a 10 GB cap, more than `org.gradle.workers.max` on a 16 GB
 * laptop, where it changes nothing. `-Pharness.heavyTasks=<n>` sets it.
 */
abstract class HeavyTaskLimit : BuildService<BuildServiceParameters.None>

/** Slots for [totalMemoryBytes] of memory: what is left after [RESERVED_GIB], three per 4 GiB, at least two. */
fun heavyTaskSlots(totalMemoryBytes: Long): Int {
    val spare = (totalMemoryBytes / BYTES_PER_GIB).toInt() - RESERVED_GIB
    return maxOf(MINIMUM_SLOTS, spare * SLOTS_PER_FOUR_GIB / GIB_PER_SLOT_GROUP)
}

/** The build's one [HeavyTaskLimit]; the first project that asks registers it. */
fun Project.heavyTaskLimit(): Provider<HeavyTaskLimit> =
    gradle.sharedServices.registerIfAbsent("heavyTaskLimit", HeavyTaskLimit::class.java) {
        val total = (ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean)
            .totalMemorySize
        maxParallelUsages.set(
            providers
                .gradleProperty("harness.heavyTasks")
                .map(String::toInt)
                .orElse(heavyTaskSlots(total)),
        )
    }
