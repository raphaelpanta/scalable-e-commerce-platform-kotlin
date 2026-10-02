package com.ecommerce.platform.security

import com.ecommerce.platform.core.problem.Problem
import com.ecommerce.platform.problem.ProblemWriter
import org.springframework.http.HttpHeaders
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException
import org.springframework.security.web.server.ServerAuthenticationEntryPoint
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler
import org.springframework.web.server.ServerWebExchange

/** 401 `unauthorized` problem with a `WWW-Authenticate: Bearer` challenge; never echoes why a token was rejected. */
class ProblemAuthenticationEntryPoint(
    private val writer: ProblemWriter,
) : ServerAuthenticationEntryPoint {
    // Return types inferred: the Java signatures are Mono<Void>
    override fun commence(
        exchange: ServerWebExchange,
        ex: AuthenticationException,
    ) = (ex is InvalidBearerTokenException).let { invalidToken ->
        val detail = if (invalidToken) "The access token is invalid or expired." else "Authentication is required."
        val challenge = if (invalidToken) "Bearer error=\"invalid_token\"" else "Bearer"
        writer.write(exchange, Problem.unauthorized(detail), mapOf(HttpHeaders.WWW_AUTHENTICATE to challenge))
    }
}

/** 403 `forbidden` problem. */
class ProblemAccessDeniedHandler(
    private val writer: ProblemWriter,
) : ServerAccessDeniedHandler {
    override fun handle(
        exchange: ServerWebExchange,
        denied: AccessDeniedException,
    ) = writer.write(exchange, Problem.forbidden("You are not allowed to perform this operation."))
}
