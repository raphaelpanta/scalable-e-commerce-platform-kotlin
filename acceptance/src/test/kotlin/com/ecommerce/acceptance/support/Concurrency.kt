package com.ecommerce.acceptance.support

import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Runs actions at the same instant: every task waits on a start latch that opens once all of them are ready. */
object Concurrency {
    private val timeout: Duration = Duration.ofSeconds(60)

    fun <T> together(tasks: List<() -> T>): List<T> {
        val ready = CountDownLatch(tasks.size)
        val start = CountDownLatch(1)
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val futures =
                tasks.map { task ->
                    executor.submit(
                        Callable {
                            ready.countDown()
                            start.await()
                            task()
                        },
                    )
                }
            check(ready.await(timeout.seconds, TimeUnit.SECONDS)) { "The concurrent tasks did not all start" }
            start.countDown()
            return futures.map { it.get(timeout.seconds, TimeUnit.SECONDS) }
        }
    }
}
