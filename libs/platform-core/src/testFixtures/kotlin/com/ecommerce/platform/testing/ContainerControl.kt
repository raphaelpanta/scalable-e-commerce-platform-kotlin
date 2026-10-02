package com.ecommerce.platform.testing

import org.testcontainers.containers.GenericContainer

/**
 * Pauses and resumes a Testcontainers container to simulate an unreachable dependency. The container engine's
 * API socket (a Podman shim locally) occasionally drops a response while many containers run in parallel, so
 * each command is retried a few times before the test gives up.
 */
object ContainerControl {
    private const val ATTEMPTS = 4
    private const val PAUSE_BETWEEN_ATTEMPTS_MS = 500L

    fun pause(container: GenericContainer<*>) {
        retrying { container.dockerClient.pauseContainerCmd(container.containerId).exec() }
    }

    /** Resumes the container when it is paused; a no-op otherwise. */
    fun unpauseIfPaused(container: GenericContainer<*>) {
        val paused = retrying { container.currentContainerInfo.state.paused == true }
        if (paused) retrying { container.dockerClient.unpauseContainerCmd(container.containerId).exec() }
    }

    // docker-java wraps the dropped socket response in a plain RuntimeException, so nothing narrower can be caught.
    @Suppress("TooGenericExceptionCaught")
    private fun <T> retrying(command: () -> T): T {
        var failure: RuntimeException? = null
        repeat(ATTEMPTS) { attempt ->
            try {
                return command()
            } catch (transient: RuntimeException) {
                failure = transient
                if (attempt < ATTEMPTS - 1) Thread.sleep(PAUSE_BETWEEN_ATTEMPTS_MS * (attempt + 1))
            }
        }
        throw checkNotNull(failure)
    }
}
