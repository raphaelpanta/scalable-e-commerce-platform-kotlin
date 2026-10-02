package com.ecommerce.platform.security

import com.ecommerce.platform.problem.PlatformProblemAutoConfiguration
import com.ecommerce.platform.problem.ProblemWriter
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.security.autoconfigure.ReactiveUserDetailsServiceAutoConfiguration
import org.springframework.boot.security.autoconfigure.actuate.web.reactive.ReactiveManagementWebSecurityAutoConfiguration
import org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.reactive.ReactiveOAuth2ResourceServerAutoConfiguration
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.reactive.ReactiveOAuth2ResourceServerWebSecurityAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpHeaders
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.SecurityWebFiltersOrder
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtAudienceValidator
import org.springframework.security.oauth2.jwt.JwtClaimNames
import org.springframework.security.oauth2.jwt.JwtClaimValidator
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.header.ServerHttpHeadersWriter
import org.springframework.security.web.server.header.StaticServerHttpHeadersWriter
import org.springframework.security.web.server.header.XFrameOptionsServerHttpHeadersWriter
import org.springframework.security.web.server.util.matcher.OrServerWebExchangeMatcher
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.reactive.CorsConfigurationSource
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.Instant
import java.util.UUID

/**
 * The resource-server security every service gets by depending on platform-core (FR-005, research section 9):
 * bearer JWTs (EdDSA) validated against `platform.security.jwks-uri`, issuer and audience; `roles` claim mapped to
 * `ROLE_shopper` / `ROLE_operator`; deny by default except the configured public paths, the management endpoints
 * (internal management port) and everything under `/internal/` (guarded by [InternalTokenWebFilter]); 401/403 as
 * `application/problem+json`; security headers and a CORS allow-list. A module that declares its own
 * `SecurityWebFilterChain` (the gateway) or sets `platform.security.enabled=false` replaces the chain.
 */
@AutoConfiguration(
    after = [PlatformProblemAutoConfiguration::class],
    before = [
        ReactiveUserDetailsServiceAutoConfiguration::class,
        ReactiveWebSecurityAutoConfiguration::class,
        ReactiveManagementWebSecurityAutoConfiguration::class,
        ReactiveOAuth2ResourceServerAutoConfiguration::class,
        ReactiveOAuth2ResourceServerWebSecurityAutoConfiguration::class,
    ],
)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnClass(ServerHttpSecurity::class, ReactiveJwtDecoder::class)
@ConditionalOnBooleanProperty(name = ["platform.security.enabled"], matchIfMissing = true)
@EnableConfigurationProperties(PlatformSecurityProperties::class)
@EnableWebFluxSecurity
class PlatformSecurityAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun platformJwtDecoder(
        properties: PlatformSecurityProperties,
        webClientBuilder: ObjectProvider<WebClient.Builder>,
    ): ReactiveJwtDecoder {
        val webClient = webClientBuilder.getIfAvailable(WebClient::builder).build()
        val decoder =
            NimbusReactiveJwtDecoder(Ed25519JwksJwtProcessor(properties.jwksUri, webClient, properties.jwksCacheTtl))
        decoder.setJwtValidator(tokenValidator(properties))
        return decoder
    }

    @Bean
    @ConditionalOnMissingBean(name = ["platformCorsConfigurationSource"])
    fun platformCorsConfigurationSource(properties: PlatformSecurityProperties): CorsConfigurationSource =
        UrlBasedCorsConfigurationSource().apply {
            if (properties.cors.allowedOrigins.isNotEmpty()) {
                registerCorsConfiguration("/**", corsConfiguration(properties.cors.allowedOrigins))
            }
        }

    @Bean
    @ConditionalOnMissingBean(SecurityWebFilterChain::class)
    fun platformSecurityWebFilterChain(
        http: ServerHttpSecurity,
        properties: PlatformSecurityProperties,
        decoder: ReactiveJwtDecoder,
        writer: ProblemWriter,
        platformCorsConfigurationSource: CorsConfigurationSource,
    ): SecurityWebFilterChain {
        val publicPaths = publicMatcher(properties)
        val entryPoint = ProblemAuthenticationEntryPoint(writer)
        val accessDenied = ProblemAccessDeniedHandler(writer)
        return http
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .requestCache { it.disable() }
            .cors { it.configurationSource(platformCorsConfigurationSource) }
            .headers { headers ->
                headers
                    .hsts { it.disable() }
                    .cache { it.disable() }
                    .frameOptions { it.mode(XFrameOptionsServerHttpHeadersWriter.Mode.DENY) }
                    .contentSecurityPolicy { it.policyDirectives(CONTENT_SECURITY_POLICY) }
                    .writer(StaticServerHttpHeadersWriter.builder().header(HSTS_HEADER, HSTS_VALUE).build())
                    .writer(noStoreUnlessPublic(publicPaths))
            }.exceptionHandling { it.authenticationEntryPoint(entryPoint).accessDeniedHandler(accessDenied) }
            .addFilterBefore(
                InternalTokenWebFilter(properties.internalToken, writer),
                SecurityWebFiltersOrder.AUTHENTICATION,
            ).authorizeExchange { exchanges ->
                exchanges
                    .matchers(publicPaths)
                    .permitAll()
                    .pathMatchers(InternalTokenWebFilter.PATTERN, ACTUATOR_PATTERN)
                    .permitAll()
                    .anyExchange()
                    .authenticated()
            }.oauth2ResourceServer { resourceServer ->
                resourceServer
                    .authenticationEntryPoint(entryPoint)
                    .accessDeniedHandler(accessDenied)
                    .jwt { it.jwtDecoder(decoder).jwtAuthenticationConverter(authenticationConverter()) }
            }.build()
    }

    companion object {
        private const val HSTS_HEADER = "Strict-Transport-Security"
        private const val HSTS_VALUE = "max-age=31536000 ; includeSubDomains"
        private const val CONTENT_SECURITY_POLICY =
            "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"
        private const val ACTUATOR_PATTERN = "/actuator/**"
        private const val NO_STORE = "no-store"
        private val CORS_HEADERS =
            listOf(
                HttpHeaders.AUTHORIZATION,
                HttpHeaders.CONTENT_TYPE,
                HttpHeaders.IF_MATCH,
                "X-Correlation-Id",
                "X-Cart-Token",
                "Idempotency-Key",
            )
        private val EXPOSED_HEADERS =
            listOf("X-Correlation-Id", "X-Cart-Token", HttpHeaders.RETRY_AFTER, HttpHeaders.LOCATION, HttpHeaders.ETAG)
        private val CORS_METHODS = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")

        /** Signature (by the processor), issuer, audience, `exp`/`nbf` and a UUID `sub`. */
        fun tokenValidator(properties: PlatformSecurityProperties): OAuth2TokenValidator<Jwt> =
            DelegatingOAuth2TokenValidator(
                JwtValidators.createDefaultWithIssuer(properties.issuer),
                JwtAudienceValidator(properties.audience),
                // JwtClaimValidator fails on an absent claim, so `exp` becomes mandatory
                JwtClaimValidator<Instant>(JwtClaimNames.EXP) { true },
                JwtClaimValidator<String>(JwtClaimNames.SUB) { runCatching { UUID.fromString(it) }.isSuccess },
            )

        /** `roles` claim to `ROLE_<role>` authorities; the principal stays the [Jwt]. */
        fun authenticationConverter(): ReactiveJwtAuthenticationConverterAdapter {
            val authorities =
                JwtGrantedAuthoritiesConverter().apply {
                    setAuthoritiesClaimName(AccountPrincipal.ROLES_CLAIM)
                    setAuthorityPrefix(Role.AUTHORITY_PREFIX)
                }
            val converter = JwtAuthenticationConverter().apply { setJwtGrantedAuthoritiesConverter(authorities) }
            return ReactiveJwtAuthenticationConverterAdapter(converter)
        }

        private fun publicMatcher(properties: PlatformSecurityProperties): ServerWebExchangeMatcher {
            val matchers =
                properties.parsedPublicPaths.map { path ->
                    if (path.method == null) {
                        ServerWebExchangeMatchers.pathMatchers(path.pattern)
                    } else {
                        ServerWebExchangeMatchers.pathMatchers(path.method, path.pattern)
                    }
                }
            return if (matchers.isEmpty()) {
                ServerWebExchangeMatcher { ServerWebExchangeMatcher.MatchResult.notMatch() }
            } else {
                OrServerWebExchangeMatcher(matchers)
            }
        }

        /** `Cache-Control: no-store` on every response except anonymous calls to public paths. */
        private fun noStoreUnlessPublic(publicPaths: ServerWebExchangeMatcher): ServerHttpHeadersWriter =
            ServerHttpHeadersWriter { exchange ->
                val authenticated = exchange.request.headers.containsHeader(HttpHeaders.AUTHORIZATION)
                publicPaths
                    .matches(exchange)
                    .map { it.isMatch && !authenticated }
                    .flatMap { anonymousPublic ->
                        if (!anonymousPublic) exchange.response.headers.set(HttpHeaders.CACHE_CONTROL, NO_STORE)
                        Mono.empty()
                    }
            }

        private fun corsConfiguration(origins: List<String>): CorsConfiguration =
            CorsConfiguration().apply {
                allowedOrigins = origins
                allowedMethods = CORS_METHODS
                allowedHeaders = CORS_HEADERS
                exposedHeaders = EXPOSED_HEADERS
                allowCredentials = false
            }
    }
}
