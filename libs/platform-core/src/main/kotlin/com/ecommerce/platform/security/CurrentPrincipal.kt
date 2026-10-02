package com.ecommerce.platform.security

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.ecommerce.platform.core.problem.Problem
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import java.util.UUID

/** The roles carried in the `roles` claim of an access token. */
enum class Role(
    val claim: String,
) {
    SHOPPER("shopper"),
    OPERATOR("operator"),
    ;

    /** The Spring Security authority (`ROLE_shopper`, `ROLE_operator`). */
    val authority: String get() = AUTHORITY_PREFIX + claim

    companion object {
        /** Prefix of role authorities. */
        const val AUTHORITY_PREFIX: String = "ROLE_"

        /** The role named [claim] in a token, if it is a known one. */
        fun fromClaim(claim: String): Role? = entries.firstOrNull { it.claim == claim }
    }
}

/** The authenticated account of a request: the token subject and its known roles. */
data class AccountPrincipal(
    val accountId: UUID,
    val roles: Set<Role>,
) {
    /** True when the account holds [role]. */
    fun has(role: Role): Boolean = role in roles

    companion object {
        /**
         * The principal of a validated access token: `sub` must be a UUID (the resource server already rejects other
         * tokens); unknown role names are ignored.
         */
        fun of(jwt: Jwt): AccountPrincipal? {
            val accountId = jwt.subject?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
            val roles =
                jwt
                    .getClaimAsStringList(ROLES_CLAIM)
                    .orEmpty()
                    .mapNotNull(Role::fromClaim)
                    .toSet()
            return AccountPrincipal(accountId, roles)
        }

        /** The principal of an [authentication] made from an access token, or `null` (anonymous, other kinds). */
        fun of(authentication: Authentication?): AccountPrincipal? =
            (authentication?.takeIf { it.isAuthenticated }?.principal as? Jwt)?.let(::of)

        /** Claim holding the role names. */
        const val ROLES_CLAIM: String = "roles"
    }
}

/** The account of the request being handled, or `null` for an anonymous request. */
suspend fun currentAccount(): AccountPrincipal? =
    AccountPrincipal.of(ReactiveSecurityContextHolder.getContext().awaitSingleOrNull()?.authentication)

/** The account of the request, or a 401 `unauthorized` problem. */
suspend fun requireAccount(): Either<Problem, AccountPrincipal> =
    currentAccount()?.right() ?: Problem.unauthorized("Authentication is required.").left()

/** The account of the request when it holds [role]; 401 when anonymous, 403 `forbidden` otherwise. */
suspend fun requireRole(role: Role): Either<Problem, AccountPrincipal> = requireAccount().flatMapRole(role)

/** The account of the request when it is an operator (401/403 problem otherwise). */
suspend fun requireOperator(): Either<Problem, AccountPrincipal> = requireRole(Role.OPERATOR)

/** The account of the request when it is a shopper (401/403 problem otherwise). */
suspend fun requireShopper(): Either<Problem, AccountPrincipal> = requireRole(Role.SHOPPER)

/** Narrows an authenticated principal to one holding [role]. */
fun Either<Problem, AccountPrincipal>.flatMapRole(role: Role): Either<Problem, AccountPrincipal> =
    when (this) {
        is Either.Left -> {
            this
        }

        is Either.Right -> {
            if (value.has(role)) {
                this
            } else {
                Problem.forbidden("This operation requires the ${role.claim} role.").left()
            }
        }
    }
