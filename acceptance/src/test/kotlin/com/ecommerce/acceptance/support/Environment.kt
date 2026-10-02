package com.ecommerce.acceptance.support

import java.time.Duration

/**
 * Everything the suite reads from the environment (documented in docs/acceptance.md). Only `GATEWAY_URL` is
 * mandatory; the Gradle `test` task is skipped when it is absent. Credentials are read here and never logged.
 */
object Environment {
    /** The single public entry point of the platform (FR-023), for example `http://localhost:8080`. */
    val gatewayUrl: String by lazy {
        checkNotNull(System.getenv("GATEWAY_URL")?.trimEnd('/')) { "GATEWAY_URL must point at a running stack" }
    }

    /** Mailpit web API: the email sink of the Compose stack. */
    val mailpitUrl: String = variable("MAILPIT_URL", "http://localhost:8025").trimEnd('/')

    /** Grafana, the only published entry point of the observability stack (Loki and Prometheus sit behind it). */
    val grafanaUrl: String = variable("GRAFANA_URL", "http://localhost:3000").trimEnd('/')
    val grafanaUser: String = variable("GRAFANA_USER", "admin")
    val grafanaPassword: String = variable("GRAFANA_PASSWORD", "admin-local-only")

    /**
     * Loki is not published to the host by the Compose stack, so log queries go through Grafana's Loki data source
     * proxy by default. Set `LOKI_VIA_GRAFANA=false` to query `LOKI_URL` directly (for example from inside the
     * platform network or when Loki's port is published).
     */
    val lokiUrl: String = variable("LOKI_URL", "http://localhost:3100").trimEnd('/')
    val lokiViaGrafana: Boolean = variable("LOKI_VIA_GRAFANA", "true").toBoolean()

    /** The operator account created by the identity seed (`SEED=true`). */
    val operatorEmail: String = variable("OPERATOR_EMAIL", "operator@ecommerce.example")
    val operatorPassword: String = variable("OPERATOR_PASSWORD", "Operator-Passw0rd!2026")

    /** Currency of every price in the platform (`PLATFORM_CURRENCY` of the services). */
    val currency: String = variable("PLATFORM_CURRENCY", "BRL")

    /** How long a failing email channel may take to exhaust its retries before the message is reported failed. */
    val deliveryFailureBudget: Duration =
        Duration.ofMinutes(variable("NOTIFICATION_FAILURE_TIMEOUT_MINUTES", "15").toLong())

    private fun variable(
        name: String,
        default: String,
    ): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default
}

/** Time budgets shared by the steps. */
object Budgets {
    /** SC-005: every order and account event produces a notification attempt within 30 seconds. */
    val notification: Duration = Duration.ofSeconds(30)

    /** Asynchronous state changes inside the platform (stock commit, refunds, history). */
    val settle: Duration = Duration.ofSeconds(15)

    /** Period during which a count must not grow, to prove that no duplicate is produced. */
    val quietPeriod: Duration = Duration.ofSeconds(10)

    /** Log shipping through the collector into Loki, and one Prometheus scrape interval plus margin. */
    val telemetry: Duration = Duration.ofSeconds(90)

    /** Waiting for a rate limit of the gateway (`auth` tier: per minute) to admit a set-up request again. */
    val rateLimit: Duration = Duration.ofSeconds(90)

    val poll: Duration = Duration.ofMillis(500)
}
