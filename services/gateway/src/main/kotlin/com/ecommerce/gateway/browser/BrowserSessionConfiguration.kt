package com.ecommerce.gateway.browser

import com.ecommerce.gateway.config.GatewayProperties
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.Clock

/** The profiles under which a missing `BROWSER_SESSION_KEY` is replaced by a throw-away key (like identity's). */
private const val KEY_GENERATING_PROFILES = "dev | test"

/**
 * Wiring of the browser-session capability: the sealing key (`gateway.browser-session.key`, required outside the
 * `dev` and `test` profiles), the pure rules, the identity refresh client and the two global filters.
 */
@Configuration(proxyBeanMethods = false)
class BrowserSessionConfiguration {
    @Bean
    fun sessionSealer(
        properties: GatewayProperties,
        environment: Environment,
    ): SessionSealer {
        val generationAllowed = environment.acceptsProfiles(Profiles.of(KEY_GENERATING_PROFILES))
        val key = BrowserSessionKeys.configured(properties.browserSession.key, generationAllowed)
        return AesGcmSessionSealer(mapOf(BrowserSessionKeys.ACTIVE_KEY_ID to key), BrowserSessionKeys.ACTIVE_KEY_ID)
    }

    @Bean
    fun browserSessionRules(properties: GatewayProperties): BrowserSessionRules =
        properties.browserSession.let { BrowserSessionRules(it.idleTimeout, it.refreshAhead) }

    @Bean
    fun refreshTokens(
        properties: GatewayProperties,
        webClientBuilder: ObjectProvider<WebClient.Builder>,
    ): RefreshTokens =
        properties.browserSession.let {
            IdentityRefreshClient(
                webClientBuilder.getIfAvailable(WebClient::builder).build(),
                it.identityUrl,
                it.refreshTimeout,
            )
        }

    @Bean
    fun browserClock(): Clock = Clock.systemUTC()

    @Bean
    fun bearerAuthenticator(
        jwtDecoder: ReactiveJwtDecoder,
        authenticationConverter: Converter<Jwt, Mono<AbstractAuthenticationToken>>,
    ): BearerAuthenticator = BearerAuthenticator(jwtDecoder, authenticationConverter)

    @Bean
    fun browserSessionFilter(
        sealer: SessionSealer,
        rules: BrowserSessionRules,
        refreshTokens: RefreshTokens,
        authenticator: BearerAuthenticator,
        clock: Clock,
    ): BrowserSessionFilter = BrowserSessionFilter(sealer, rules, refreshTokens, authenticator, clock)

    @Bean
    fun cartCookieFilter(
        sealer: SessionSealer,
        properties: GatewayProperties,
    ): CartCookieFilter = CartCookieFilter(sealer, properties.browserSession.cartCookieMaxAge)
}
