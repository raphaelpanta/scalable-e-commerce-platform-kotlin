package com.ecommerce.identity.infrastructure.web

import com.ecommerce.platform.observability.observedCoRouter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse

private const val IDENTITY = "/api/v1/identity"
private const val ME = "$IDENTITY/accounts/me"

/**
 * The HTTP surface: the 18 operations of identity.yaml (public, through the gateway), the two operations of
 * identity-internal.yaml under `/internal/` and the JWKS document (public, not routed by the gateway).
 */
@Configuration(proxyBeanMethods = false)
class IdentityRoutes {
    @Bean
    @Suppress("LongParameterList") // one handler class per tag of identity.yaml
    fun identityRouter(
        registration: RegistrationHandlers,
        sessions: SessionHandlers,
        resets: PasswordResetHandlers,
        profile: ProfileHandlers,
        addresses: AddressHandlers,
        preferences: PreferenceHandlers,
        internal: InternalHandlers,
        jwks: JwksHandler,
    ): RouterFunction<ServerResponse> =
        observedCoRouter {
            POST("$IDENTITY/accounts", registration::register)
            POST("$IDENTITY/accounts/verify-email", registration::verifyEmail)
            POST("$IDENTITY/sessions", sessions::signIn)
            POST("$IDENTITY/sessions/refresh", sessions::refresh)
            DELETE("$IDENTITY/sessions/current", sessions::signOut)
            POST("$IDENTITY/password-resets", resets::request)
            POST("$IDENTITY/password-resets/complete", resets::complete)
            GET(ME, profile::get)
            PUT(ME, profile::update)
            DELETE(ME, profile::delete)
            GET("$ME/addresses", addresses::list)
            POST("$ME/addresses", addresses::add)
            PUT("$ME/addresses/{addressId}", addresses::replace)
            DELETE("$ME/addresses/{addressId}", addresses::remove)
            GET("$ME/notification-preferences", preferences::get)
            PUT("$ME/notification-preferences", preferences::update)
            POST("$ME/phone-verifications", preferences::requestCode)
            POST("$ME/phone-verifications/confirm", preferences::confirmCode)
            GET("/internal/accounts/{accountId}/addresses/{addressId}", internal::address)
            GET("/internal/accounts/{accountId}/contact", internal::contact)
            GET("/.well-known/jwks.json", jwks::jwks)
        }
}
