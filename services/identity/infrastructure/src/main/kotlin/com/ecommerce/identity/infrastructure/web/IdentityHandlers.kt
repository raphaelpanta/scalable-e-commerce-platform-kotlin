package com.ecommerce.identity.infrastructure.web

import arrow.core.raise.either
import com.ecommerce.identity.application.AddAddress
import com.ecommerce.identity.application.AddressInput
import com.ecommerce.identity.application.CompletePasswordReset
import com.ecommerce.identity.application.ConfirmPhoneVerification
import com.ecommerce.identity.application.Credentials
import com.ecommerce.identity.application.DeleteAccount
import com.ecommerce.identity.application.GetAccountAddress
import com.ecommerce.identity.application.GetAccountContact
import com.ecommerce.identity.application.GetNotificationPreferences
import com.ecommerce.identity.application.GetProfile
import com.ecommerce.identity.application.ListAddresses
import com.ecommerce.identity.application.RefreshSession
import com.ecommerce.identity.application.RegisterAccount
import com.ecommerce.identity.application.Registration
import com.ecommerce.identity.application.RemoveAddress
import com.ecommerce.identity.application.ReplaceAddress
import com.ecommerce.identity.application.RequestPasswordReset
import com.ecommerce.identity.application.RequestPhoneVerification
import com.ecommerce.identity.application.ResetCompletion
import com.ecommerce.identity.application.SignIn
import com.ecommerce.identity.application.SignOut
import com.ecommerce.identity.application.UpdateNotificationPreferences
import com.ecommerce.identity.application.UpdateProfile
import com.ecommerce.identity.application.VerifyEmail
import com.ecommerce.identity.domain.AccountId
import com.ecommerce.identity.domain.AddressId
import com.ecommerce.identity.domain.PostalAddress
import com.ecommerce.identity.domain.SessionId
import com.ecommerce.identity.infrastructure.security.JwtTokenSigner
import com.ecommerce.identity.infrastructure.security.SigningKeyRing
import com.ecommerce.platform.core.problem.Problem
import com.ecommerce.platform.core.values.PageRequest
import com.ecommerce.platform.problem.ProblemResponses
import com.ecommerce.platform.security.requireShopper
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.buildAndAwait
import java.net.URI
import java.util.UUID

private const val FORWARDED_FOR = "X-Forwarded-For"
private const val UNKNOWN_SOURCE = "unknown"

private suspend fun json(
    status: HttpStatus,
    body: Any,
): ServerResponse = ServerResponse.status(status).contentType(MediaType.APPLICATION_JSON).bodyValueAndAwait(body)

private suspend fun ok(body: Any): ServerResponse = json(HttpStatus.OK, body)

private suspend fun noContent(): ServerResponse = ServerResponse.noContent().buildAndAwait()

/**
 * The caller's source address for the sign-in throttle: the first `X-Forwarded-For` entry (set by the gateway, the
 * only client of the internal network) or else the peer address.
 */
internal fun sourceOf(request: ServerRequest): String =
    request
        .headers()
        .firstHeader(FORWARDED_FOR)
        ?.substringBefore(',')
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: request.remoteAddress().map { it.address?.hostAddress ?: it.hostString }.orElse(UNKNOWN_SOURCE)

/** `registerAccount` and `verifyEmail` (anonymous). */
class RegistrationHandlers(
    private val registerAccount: RegisterAccount,
    private val verifyEmail: VerifyEmail,
) {
    suspend fun register(request: ServerRequest): ServerResponse =
        either {
            val body = request.jsonBody<RegisterRequest>().bind()
            val registration =
                Registration(
                    body.email.required("email").bind(),
                    body.password.required("password").bind(),
                    body.displayName,
                )
            registerAccount(registration).refused().bind()
        }.respond(request) { json(HttpStatus.ACCEPTED, IdentityJson.message(IdentityJson.REGISTRATION_ACCEPTED)) }

    suspend fun verifyEmail(request: ServerRequest): ServerResponse =
        either {
            val token =
                request
                    .jsonBody<TokenRequest>()
                    .bind()
                    .token
                    .required("token")
                    .bind()
            verifyEmail(token).refused().bind()
        }.respond(request) { noContent() }
}

/** `signIn`, `refreshSession` (anonymous) and `signOut`. */
class SessionHandlers(
    private val signIn: SignIn,
    private val refreshSession: RefreshSession,
    private val signOut: SignOut,
) {
    suspend fun signIn(request: ServerRequest): ServerResponse =
        either {
            val body = request.jsonBody<SignInRequest>().bind()
            val credentials =
                Credentials(
                    body.email.required("email").bind(),
                    body.password.required("password").bind(),
                    sourceOf(request),
                )
            signIn(credentials).refused().bind()
        }.respond(request) { ok(IdentityJson.tokens(it)) }

    suspend fun refresh(request: ServerRequest): ServerResponse =
        either {
            val token =
                request
                    .jsonBody<RefreshRequest>()
                    .bind()
                    .refreshToken
                    .required("refreshToken")
                    .bind()
            refreshSession(token).refused().bind()
        }.respond(request) { ok(IdentityJson.tokens(it)) }

    suspend fun signOut(request: ServerRequest): ServerResponse =
        either {
            val principal = principal().bind()
            signOut(AccountId(principal.accountId), currentSessionId())
        }.respond(request) { noContent() }

    private suspend fun currentSessionId(): SessionId? {
        val jwt =
            ReactiveSecurityContextHolder
                .getContext()
                .awaitSingleOrNull()
                ?.authentication
                ?.principal as? Jwt
        val claim = jwt?.getClaimAsString(JwtTokenSigner.SESSION)
        return claim?.let { runCatching { SessionId(UUID.fromString(it)) }.getOrNull() }
    }
}

/** `requestPasswordReset` and `completePasswordReset` (anonymous). */
class PasswordResetHandlers(
    private val requestPasswordReset: RequestPasswordReset,
    private val completePasswordReset: CompletePasswordReset,
) {
    suspend fun request(request: ServerRequest): ServerResponse =
        either {
            val email =
                request
                    .jsonBody<PasswordResetRequest>()
                    .bind()
                    .email
                    .required("email")
                    .bind()
            requestPasswordReset(email).refused().bind()
        }.respond(request) { json(HttpStatus.ACCEPTED, IdentityJson.message(IdentityJson.RESET_ACCEPTED)) }

    suspend fun complete(request: ServerRequest): ServerResponse =
        either {
            val body = request.jsonBody<PasswordResetCompletion>().bind()
            val completion =
                ResetCompletion(body.token.required("token").bind(), body.newPassword.required("newPassword").bind())
            completePasswordReset(completion).refused().bind()
        }.respond(request) { noContent() }
}

/** `getOwnProfile`, `updateOwnProfile` and `deleteOwnAccount`. */
class ProfileHandlers(
    private val getProfile: GetProfile,
    private val updateProfile: UpdateProfile,
    private val deleteAccount: DeleteAccount,
) {
    suspend fun get(request: ServerRequest): ServerResponse =
        either {
            getProfile(AccountId(principal().bind().accountId)).refused().bind()
        }.respond(request) { ok(IdentityJson.account(it)) }

    suspend fun update(request: ServerRequest): ServerResponse =
        either {
            val accountId = AccountId(principal().bind().accountId)
            val displayName =
                request
                    .jsonBody<ProfileUpdate>()
                    .bind()
                    .displayName
                    .required("displayName")
                    .bind()
            updateProfile(accountId, displayName).refused().bind()
        }.respond(request) { ok(IdentityJson.account(it)) }

    suspend fun delete(request: ServerRequest): ServerResponse =
        either {
            val shopper = requireShopper().mapLeft(::Refusal).bind()
            deleteAccount(AccountId(shopper.accountId)).refused().bind()
        }.respond(request) { noContent() }
}

/** `listOwnAddresses`, `addOwnAddress`, `updateOwnAddress` and `deleteOwnAddress`. */
class AddressHandlers(
    private val listAddresses: ListAddresses,
    private val addAddress: AddAddress,
    private val replaceAddress: ReplaceAddress,
    private val removeAddress: RemoveAddress,
) {
    suspend fun list(request: ServerRequest): ServerResponse =
        either {
            val accountId = AccountId(principal().bind().accountId)
            val page =
                PageRequest
                    .fromQuery(request.queryParam("page").orElse(null), request.queryParam("size").orElse(null))
                    .mapLeft { badRequest(it.field, it.reason) }
                    .bind()
            page to listAddresses(accountId, page.offset, page.size).refused().bind()
        }.respond(request) { (page, addresses) -> ok(IdentityJson.addressPage(addresses, page.page, page.size)) }

    suspend fun add(request: ServerRequest): ServerResponse =
        either {
            val accountId = AccountId(principal().bind().accountId)
            val input = inputOf(request.jsonBody<AddressInputJson>().bind())
            addAddress(accountId, input).refused().bind()
        }.respond(request) { address ->
            ServerResponse
                .created(URI.create("/api/v1/identity/accounts/me/addresses/${address.id.value}"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValueAndAwait(IdentityJson.address(address))
        }

    suspend fun replace(request: ServerRequest): ServerResponse =
        either {
            val accountId = AccountId(principal().bind().accountId)
            val addressId = AddressId(request.uuid("addressId").bind())
            val input = inputOf(request.jsonBody<AddressInputJson>().bind())
            replaceAddress(accountId, addressId, input).refused().bind()
        }.respond(request) { ok(IdentityJson.address(it)) }

    suspend fun remove(request: ServerRequest): ServerResponse =
        either {
            val accountId = AccountId(principal().bind().accountId)
            removeAddress(accountId, AddressId(request.uuid("addressId").bind())).refused().bind()
        }.respond(request) { noContent() }

    private companion object {
        fun inputOf(body: AddressInputJson): AddressInput =
            AddressInput(
                body.label,
                PostalAddress.Fields(
                    body.recipientName,
                    body.line1,
                    body.line2,
                    body.city,
                    body.region,
                    body.postalCode,
                    body.countryCode,
                ),
                body.isDefault ?: false,
            )
    }
}

/** `get/updateOwnNotificationPreferences`, `requestPhoneVerification` and `confirmPhoneVerification`. */
class PreferenceHandlers(
    private val getPreferences: GetNotificationPreferences,
    private val updatePreferences: UpdateNotificationPreferences,
    private val requestPhoneVerification: RequestPhoneVerification,
    private val confirmPhoneVerification: ConfirmPhoneVerification,
) {
    suspend fun get(request: ServerRequest): ServerResponse =
        either {
            getPreferences(AccountId(principal().bind().accountId)).refused().bind()
        }.respond(request) { ok(IdentityJson.preferences(it)) }

    suspend fun update(request: ServerRequest): ServerResponse =
        either {
            val accountId = AccountId(principal().bind().accountId)
            val channels =
                request
                    .jsonBody<PreferencesUpdate>()
                    .bind()
                    .channels
                    .required("channels")
                    .bind()
            updatePreferences(accountId, channels).refused().bind()
        }.respond(request) { ok(IdentityJson.preferences(it)) }

    suspend fun requestCode(request: ServerRequest): ServerResponse =
        either {
            val accountId = AccountId(principal().bind().accountId)
            val phone =
                request
                    .jsonBody<PhoneVerificationRequest>()
                    .bind()
                    .phoneNumber
                    .required("phoneNumber")
                    .bind()
            requestPhoneVerification(accountId, phone).refused().bind()
        }.respond(request) { ServerResponse.accepted().buildAndAwait() }

    suspend fun confirmCode(request: ServerRequest): ServerResponse =
        either {
            val accountId = AccountId(principal().bind().accountId)
            val code =
                request
                    .jsonBody<PhoneVerificationConfirmation>()
                    .bind()
                    .code
                    .required("code")
                    .bind()
            confirmPhoneVerification(accountId, code).refused().bind()
        }.respond(request) { noContent() }
}

/**
 * The internal API (identity-internal.yaml), reached only with a valid `X-Internal-Token` (platform-core's
 * `InternalTokenWebFilter` answers 401 before this runs).
 */
class InternalHandlers(
    private val getAccountAddress: GetAccountAddress,
    private val getAccountContact: GetAccountContact,
) {
    suspend fun address(request: ServerRequest): ServerResponse =
        either {
            val accountId = AccountId(request.uuid("accountId").bind())
            val addressId = AddressId(request.uuid("addressId").bind())
            getAccountAddress(accountId, addressId).refusedInternally().bind()
        }.respond(request) { ok(IdentityJson.postal(it)) }

    suspend fun contact(request: ServerRequest): ServerResponse =
        either {
            getAccountContact(AccountId(request.uuid("accountId").bind())).refusedInternally().bind()
        }.respond(request) { ok(IdentityJson.contact(it)) }
}

/** `GET /.well-known/jwks.json`: the published signing keys, or 503 `unavailable` while there is none. */
class JwksHandler(
    private val ring: SigningKeyRing,
) {
    suspend fun jwks(request: ServerRequest): ServerResponse {
        val keys = ring.jwkSet()
        return if (keys == null) {
            ProblemResponses.of(request, Problem.unavailable("No signing key is available."))
        } else {
            ok(keys.toJSONObject())
        }
    }
}
