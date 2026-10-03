package com.ecommerce.gateway.routing

import org.springframework.cloud.gateway.config.HttpClientCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import reactor.netty.http.client.HttpClient
import java.time.Duration

/**
 * DNS resolution of the upstream services for instance churn (FR-024, SC-008). Each route's `uri` is a service name
 * that the platform DNS resolves to every healthy instance; the upstream client resolves it with Reactor Netty's
 * DNS resolver, keeps an answer for at most [MAX_TIME_TO_LIVE] (a removed instance drops out within seconds, an added
 * one starts receiving traffic as soon) and selects the returned addresses round-robin, so the load spreads over the
 * instances. Retries stay where application.yml declares them: GET and HEAD only, on `ConnectException` only.
 * The same settings as the services' internal clients (platform-core `WebClientDefaults.httpClient`); the gateway
 * does not depend on platform-core.
 */
object UpstreamResolver {
    val MAX_TIME_TO_LIVE: Duration = Duration.ofSeconds(5)

    /** [client] with the 5-second, round-robin DNS resolver. */
    fun configure(client: HttpClient): HttpClient =
        client.resolver { spec ->
            spec
                .cacheMaxTimeToLive(MAX_TIME_TO_LIVE)
                .roundRobinSelection(true)
        }
}

/** Applies [UpstreamResolver] to the Netty client Spring Cloud Gateway forwards requests with. */
@Configuration(proxyBeanMethods = false)
class UpstreamHttpClientConfiguration {
    @Bean
    fun upstreamResolver(): HttpClientCustomizer = HttpClientCustomizer(UpstreamResolver::configure)
}
