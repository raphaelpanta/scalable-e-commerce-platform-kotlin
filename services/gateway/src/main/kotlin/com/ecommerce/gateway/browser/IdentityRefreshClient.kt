package com.ecommerce.gateway.browser

import com.ecommerce.gateway.correlation.CorrelationIds
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.net.URI
import java.time.Duration

/** What identity answered to a refresh of the sealed refresh token. */
sealed interface RefreshOutcome {
    /** 200: the rotated pair replaces the sealed one. */
    data class Rotated(
        val tokens: TokenPair,
    ) : RefreshOutcome

    /** Identity refused the refresh token (4xx): the session is over. */
    data object Refused : RefreshOutcome

    /** Identity could not answer (5xx, unreachable, slow, unreadable body): the session is kept, the request fails. */
    data class Unavailable(
        val cause: Throwable?,
    ) : RefreshOutcome
}

/** The port the session filter refreshes through; [IdentityRefreshClient] is its adapter. */
fun interface RefreshTokens {
    fun refresh(
        refreshToken: String,
        correlationId: String?,
    ): Mono<RefreshOutcome>
}

/**
 * `POST /api/v1/identity/sessions/refresh` on `IDENTITY_URL` with a non-blocking [WebClient]: the refresh token
 * rotates there (identity.yaml `refreshSession`). The token is sent in the body only; nothing is logged.
 */
class IdentityRefreshClient(
    private val webClient: WebClient,
    identityUrl: URI,
    private val timeout: Duration,
) : RefreshTokens {
    private val refreshUri: URI = identityUrl.resolve(REFRESH_PATH)

    override fun refresh(
        refreshToken: String,
        correlationId: String?,
    ): Mono<RefreshOutcome> =
        webClient
            .post()
            .uri(refreshUri)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .headers { headers -> correlationId?.let { headers.set(CorrelationIds.HEADER, it) } }
            .bodyValue(SessionJson.refreshRequest(refreshToken))
            .exchangeToMono<RefreshOutcome> { response ->
                when {
                    response.statusCode().is2xxSuccessful -> {
                        response.bodyToMono(ByteArray::class.java).map<RefreshOutcome> { body ->
                            SessionJson.tokenPair(body)?.let(RefreshOutcome::Rotated)
                                ?: RefreshOutcome.Unavailable(null)
                        }
                    }

                    response.statusCode().is4xxClientError -> {
                        response.releaseBody().thenReturn(RefreshOutcome.Refused)
                    }

                    else -> {
                        response.releaseBody().thenReturn(RefreshOutcome.Unavailable(null))
                    }
                }
            }.timeout(timeout)
            .onErrorResume { failure -> Mono.just(RefreshOutcome.Unavailable(failure)) }

    private companion object {
        const val REFRESH_PATH = "/api/v1/identity/sessions/refresh"
    }
}
