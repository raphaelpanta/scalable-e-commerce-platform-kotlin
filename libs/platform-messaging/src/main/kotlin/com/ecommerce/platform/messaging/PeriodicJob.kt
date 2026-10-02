package com.ecommerce.platform.messaging

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import java.time.Duration
import kotlin.time.toKotlinDuration

/**
 * A background loop on its own coroutine scope, started and stopped with the application context (no
 * `@Scheduled` thread, no blocking). [step] runs, then the loop waits [interval]; a step that returns true runs
 * again at once (a full outbox batch). A failing step is logged and retried after the interval. Stopping cancels
 * the coroutine and completes the lifecycle callback once it has finished.
 */
class PeriodicJob(
    private val name: String,
    private val interval: Duration,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val step: suspend () -> Boolean,
) : SmartLifecycle {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineName(name))

    @Volatile
    private var job: Job? = null

    override fun start() {
        if (job?.isActive != true) {
            job = scope.launch { loop() }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    override fun stop(callback: Runnable) {
        val running = job
        job = null
        if (running == null) {
            callback.run()
        } else {
            running.invokeOnCompletion { callback.run() }
            running.cancel()
        }
    }

    override fun isRunning(): Boolean = job?.isActive == true

    private suspend fun CoroutineScope.loop() {
        val pause = interval.toKotlinDuration()
        while (isActive) {
            val again =
                runCatching { step() }.getOrElse { failure ->
                    if (failure is CancellationException) throw failure
                    log.warn("Messaging job '{}' failed; retrying in {}", name, interval, failure)
                    false
                }
            if (!again) delay(pause)
        }
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(PeriodicJob::class.java)
    }
}
