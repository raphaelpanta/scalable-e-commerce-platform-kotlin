package com.ecommerce.platform.http

import com.ecommerce.platform.correlation.CorrelationIds
import com.ecommerce.platform.security.InternalTokenWebFilter
import io.netty.channel.ChannelOption
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.netty.http.client.HttpClient
import reactor.netty.http.client.PrematureCloseException
import reactor.util.retry.Retry
import java.net.ConnectException
import java.net.UnknownHostException
import java.time.Duration

/**
 * The one way services build internal HTTP clients (service-to-service calls on the platform network, T104).
 *
 * Discovery relies on the platform DNS, not on Spring Cloud LoadBalancer: Compose (or any orchestrator) resolves the
 * context name (`catalog`, `payment`, ...) to every healthy instance, the resolver round-robins over the returned
 * addresses and caches them for at most 5 seconds (the JVM-wide `networkaddress.cache.ttl=5` is set for the JDK
 * resolver as well), and a request that cannot connect is retried at most [MAX_RETRIES] times, which lands on
 * another instance after a scale-down. Retries happen only on connection errors (`ConnectException`,
 * `UnknownHostException`, `PrematureCloseException`), never on an HTTP status, so 4xx/5xx answers reach the caller
 * unchanged (decode them with [awaitBodyOrProblem]). Internal endpoints that change state are idempotent by key, so
 * a retry after a premature close is safe.
 *
 * Every request carries `X-Internal-Token` (when configured) and the caller's `X-Correlation-Id` from the Reactor
 * context. Connect timeout 2 s and response timeout 5 s by default.
 */
object WebClientDefaults {
    val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(2)
    val RESPONSE_TIMEOUT: Duration = Duration.ofSeconds(5)
    const val MAX_RETRIES: Long = 2
    val RETRY_BACKOFF: Duration = Duration.ofMillis(100)
    val DNS_CACHE_TTL: Duration = Duration.ofSeconds(5)

    /**
     * An internal client for [baseUrl] built on the application's [builder] (keeps Boot's codecs and observation).
     * A blank [internalToken] sends no token header.
     */
    fun internalClient(
        builder: WebClient.Builder,
        baseUrl: String,
        internalToken: String?,
        responseTimeout: Duration = RESPONSE_TIMEOUT,
        connectTimeout: Duration = CONNECT_TIMEOUT,
    ): WebClient =
        builder
            .clone()
            .baseUrl(baseUrl)
            .clientConnector(ReactorClientHttpConnector(httpClient(responseTimeout, connectTimeout)))
            .defaultHeaders { headers ->
                if (!internalToken.isNullOrBlank()) headers.set(InternalTokenWebFilter.HEADER, internalToken)
            }.filter(propagateCorrelationId())
            .filter(retryOnConnectionError())
            .build()

    /** Reactor Netty client with the platform timeouts and a 5-second, round-robin DNS cache. */
    fun httpClient(
        responseTimeout: Duration = RESPONSE_TIMEOUT,
        connectTimeout: Duration = CONNECT_TIMEOUT,
    ): HttpClient {
        java.security.Security.setProperty("networkaddress.cache.ttl", DNS_CACHE_TTL.seconds.toString())
        return HttpClient
            .create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeout.toMillis().toInt())
            .responseTimeout(responseTimeout)
            .resolver { spec ->
                spec
                    .cacheMaxTimeToLive(DNS_CACHE_TTL)
                    .roundRobinSelection(true)
            }
    }

    /** Copies the correlation id of the Reactor context into `X-Correlation-Id` (unless the request sets one). */
    fun propagateCorrelationId(): ExchangeFilterFunction =
        ExchangeFilterFunction { request, next ->
            Mono.deferContextual { context ->
                val id = CorrelationIds.from(context)
                if (id == null || request.headers().containsHeader(CorrelationIds.HEADER)) {
                    next.exchange(request)
                } else {
                    next.exchange(ClientRequest.from(request).header(CorrelationIds.HEADER, id).build())
                }
            }
        }

    /** Retries a request at most [MAX_RETRIES] times when the connection failed; HTTP statuses are never retried. */
    fun retryOnConnectionError(
        maxRetries: Long = MAX_RETRIES,
        backoff: Duration = RETRY_BACKOFF,
    ): ExchangeFilterFunction =
        ExchangeFilterFunction { request, next ->
            next
                .exchange(request)
                .retryWhen(
                    Retry.backoff(maxRetries, backoff).filter(::isConnectionError).onRetryExhaustedThrow {
                        _,
                        signal,
                        ->
                        signal.failure()
                    },
                )
        }

    /** True when [error] (or one of its causes) is a connection failure worth retrying on another instance. */
    fun isConnectionError(error: Throwable): Boolean =
        generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }
            .take(MAX_CAUSE_DEPTH)
            .any { it is ConnectException || it is UnknownHostException || it is PrematureCloseException }

    private const val MAX_CAUSE_DEPTH = 10
}
