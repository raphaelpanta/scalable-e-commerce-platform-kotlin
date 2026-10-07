package com.ecommerce.gateway.browser

import com.ecommerce.gateway.security.JwksUnavailableException
import org.springframework.core.convert.converter.Converter
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import reactor.core.publisher.Mono

/**
 * Verifies the access token unsealed from a session cookie exactly as the resource server verifies a client's bearer
 * (same decoder, same `roles` to authorities converter), so the route access filter sees one kind of caller. An
 * invalid token ends the session (401 with the cookie deleted); unreadable signing keys answer 503, never a success.
 */
class BearerAuthenticator(
    private val jwtDecoder: ReactiveJwtDecoder,
    private val authenticationConverter: Converter<Jwt, Mono<AbstractAuthenticationToken>>,
) {
    fun authenticate(
        accessToken: String,
        arrivedAs: CookieName,
    ): Mono<AbstractAuthenticationToken> =
        jwtDecoder
            .decode(accessToken)
            .flatMap { jwt -> authenticationConverter.convert(jwt) }
            .onErrorMap(JwtException::class.java) { failure ->
                if (failure is JwksUnavailableException) {
                    BrowserProblems.tokensUnverifiable(failure)
                } else {
                    BrowserProblems.sessionExpired(listOf(BrowserCookies.delete(arrivedAs)))
                }
            }.switchIfEmpty(Mono.error { BrowserProblems.sessionExpired(listOf(BrowserCookies.delete(arrivedAs))) })
}
