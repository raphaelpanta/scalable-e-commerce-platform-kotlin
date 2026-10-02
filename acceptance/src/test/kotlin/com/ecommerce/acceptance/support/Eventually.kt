package com.ecommerce.acceptance.support

import org.awaitility.Awaitility
import java.time.Duration

/** Retries [assertion] until it passes or [within] elapses; the last assertion error is reported. */
fun eventually(
    within: Duration = Budgets.settle,
    every: Duration = Budgets.poll,
    assertion: () -> Unit,
) {
    Awaitility
        .await()
        .atMost(within)
        .pollDelay(Duration.ZERO)
        .pollInterval(every)
        .untilAsserted { assertion() }
}

/** Requires [condition] to hold for the whole of [period], to prove that nothing more happens. */
fun remainsTrue(
    period: Duration = Budgets.quietPeriod,
    condition: () -> Boolean,
) {
    Awaitility
        .await()
        .during(period)
        .atMost(period.plus(period))
        .pollInterval(Duration.ofSeconds(1))
        .until { condition() }
}

/**
 * Repeats a set-up [call] while the gateway's rate limiter answers 429, honouring the per-minute tiers of
 * `contracts/gateway-routes.md`. Never used by steps that assert throttling itself.
 */
fun untilNotThrottled(call: () -> ApiResponse): ApiResponse {
    var response = call()
    if (response.status == Status.TOO_MANY_REQUESTS) {
        Awaitility
            .await()
            .atMost(Budgets.rateLimit)
            .pollInterval(Duration.ofSeconds(5))
            .until {
                response = call()
                response.status != Status.TOO_MANY_REQUESTS
            }
    }
    return response
}
