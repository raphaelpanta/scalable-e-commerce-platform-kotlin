package com.ecommerce.gateway.browser

import com.ecommerce.gateway.web.EdgeHeaders
import org.reactivestreams.Publisher
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.http.server.reactive.ServerHttpResponseDecorator
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * Rewrites identity's 200 token pair (sign-in or refresh in browser mode) into the tokenless summary
 * `{expiresAt, roles}` and seals the tokens into the session cookie instead. Any other status passes through
 * untouched and sets no cookie. A token pair that cannot be read or sealed within the cookie budget fails closed:
 * 502 `unavailable`, no cookie, no truncated value ([BrowserProblems]).
 */
class SessionSummaryResponse(
    delegate: ServerHttpResponse,
    private val transport: Transport,
    private val sealer: SessionSealer,
    private val idleTimeout: Duration,
    private val sessionOf: (TokenPair, AccessTokenClaims) -> SealedSession,
) : ServerHttpResponseDecorator(delegate) {
    @Suppress("ForbiddenVoid") // the reactive contract is Java's Mono<Void>
    override fun writeWith(body: Publisher<out DataBuffer>): Mono<Void> {
        if (statusCode != HttpStatus.OK) return super.writeWith(body)
        return DataBufferUtils
            .join(body)
            .map(::bytesOf)
            .switchIfEmpty(Mono.error { BrowserProblems.unusableTokens() })
            .flatMap { bytes -> sessionIn(bytes)?.let(::summarise) ?: Mono.error(BrowserProblems.unusableTokens()) }
    }

    @Suppress("ForbiddenVoid") // the reactive contract is Java's Mono<Void>
    override fun writeAndFlushWith(body: Publisher<out Publisher<out DataBuffer>>): Mono<Void> =
        writeWith(Flux.from(body).flatMapSequential { it })

    /** The new session from identity's body, or null when the body is not a readable token pair. */
    private fun sessionIn(bytes: ByteArray): SealedSession? {
        val tokens = SessionJson.tokenPair(bytes) ?: return null
        return AccessTokenClaims.parse(tokens.accessToken)?.let { claims -> sessionOf(tokens, claims) }
    }

    @Suppress("ForbiddenVoid") // the reactive contract is Java's Mono<Void>
    private fun summarise(session: SealedSession): Mono<Void> {
        val cookie =
            BrowserCookies.set(transport.session, sealer.seal(session))
                ?: return Mono.error(BrowserProblems.sessionTooLarge())
        val summary = SessionJson.summary(session.expiresAt(idleTimeout), session.roles)
        headers.add(HttpHeaders.SET_COOKIE, cookie)
        headers.add(HttpHeaders.SET_COOKIE, BrowserCookies.delete(transport.session.counterpart))
        headers.set(HttpHeaders.CACHE_CONTROL, EdgeHeaders.NO_STORE)
        headers.contentType = MediaType.APPLICATION_JSON
        headers.contentLength = summary.size.toLong()
        headers.remove(HttpHeaders.TRANSFER_ENCODING)
        return super.writeWith(Mono.just(bufferFactory().wrap(summary)))
    }

    private fun bytesOf(buffer: DataBuffer): ByteArray =
        try {
            ByteArray(buffer.readableByteCount()).also(buffer::read)
        } finally {
            DataBufferUtils.release(buffer)
        }
}
