package com.ecommerce.gateway.security

import com.ecommerce.gateway.config.GatewayProperties
import com.ecommerce.gateway.problem.GatewayProblem
import com.ecommerce.gateway.problem.GatewayProblemException
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.authentication.AuthenticationServiceException
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.authentication.ServerAuthenticationFailureHandler
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono

/** Prefix of the authorities derived from the `roles` claim (`ROLE_shopper`, `ROLE_operator`). */
const val ROLE_PREFIX = "ROLE_"

/**
 * Reactive resource server: every request that carries a bearer token is authenticated against the identity JWKS,
 * also on anonymous routes (a present but invalid token answers 401, it never degrades to anonymous). Authorisation
 * is not decided here but per route by [com.ecommerce.gateway.routing.RouteAccessFilter], from the auth requirement
 * the route declares in application.yml, so the route table is the single source of truth; requests that match no
 * route answer 404. The gateway is stateless: no session, no CSRF (bearer tokens only), no request cache; security
 * headers are written by [com.ecommerce.gateway.web.EdgeHttpHandlerDecorator].
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
class SecurityConfiguration {
    @Bean
    fun jwksClient(
        properties: GatewayProperties,
        webClientBuilder: ObjectProvider<WebClient.Builder>,
    ): JwksClient =
        properties.jwt.let {
            val webClient = webClientBuilder.getIfAvailable(WebClient::builder).build()
            JwksClient(webClient, it.jwksUri, it.cacheTtl, it.refreshCooldown, it.fetchTimeout)
        }

    @Bean
    fun reactiveJwtDecoder(
        jwksClient: JwksClient,
        properties: GatewayProperties,
    ): ReactiveJwtDecoder = GatewayJwtDecoder.create(jwksClient, properties.jwt.issuer, properties.jwt.audience)

    @Bean
    fun securityWebFilterChain(
        http: ServerHttpSecurity,
        decoder: ReactiveJwtDecoder,
    ): SecurityWebFilterChain =
        http
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .headers { it.disable() }
            .requestCache { it.requestCache(NoOpServerRequestCache.getInstance()) }
            .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
            .authorizeExchange { it.anyExchange().permitAll() }
            .oauth2ResourceServer { resourceServer ->
                resourceServer
                    .jwt { jwt ->
                        jwt.jwtDecoder(decoder)
                        jwt.jwtAuthenticationConverter(authenticationConverter())
                    }.authenticationFailureHandler(failureHandler())
            }.build()

    private fun authenticationConverter(): ReactiveJwtAuthenticationConverterAdapter {
        val authorities =
            JwtGrantedAuthoritiesConverter().apply {
                setAuthoritiesClaimName(ROLES_CLAIM)
                setAuthorityPrefix(ROLE_PREFIX)
            }
        val converter = JwtAuthenticationConverter().apply { setJwtGrantedAuthoritiesConverter(authorities) }
        return ReactiveJwtAuthenticationConverterAdapter(converter)
    }

    /** Invalid tokens answer 401; a key set that cannot be read answers 503 (never a fallback to accepting). */
    private fun failureHandler() =
        ServerAuthenticationFailureHandler { _, failure ->
            Mono.error(
                if (failure is AuthenticationServiceException) {
                    GatewayProblemException(GatewayProblem.UNAVAILABLE, AUTH_UNAVAILABLE, cause = failure)
                } else {
                    GatewayProblemException(GatewayProblem.UNAUTHORIZED, INVALID_TOKEN)
                },
            )
        }

    private companion object {
        const val ROLES_CLAIM = "roles"
        const val INVALID_TOKEN = "The bearer token is invalid or expired."
        const val AUTH_UNAVAILABLE = "Access tokens cannot be verified right now. Try again later."
    }
}
