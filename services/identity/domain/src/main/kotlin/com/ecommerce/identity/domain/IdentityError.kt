package com.ecommerce.identity.domain

import java.time.Duration

/** One rejected input: the request [field] it belongs to and a short, client-safe [reason]. */
data class FieldError(
    val field: String,
    val reason: String,
)

/** Why an identity operation was refused; the web layer maps each case to its RFC 9457 problem (identity.yaml). */
sealed interface IdentityError {
    /** Well-formed input that breaks a rule (422 `validation`, one entry per broken rule). */
    data class Invalid(
        val errors: List<FieldError>,
    ) : IdentityError {
        init {
            require(errors.isNotEmpty()) { "an invalid input names at least one broken rule" }
        }
    }

    /** Input that does not have the documented shape (400 `validation`). */
    data class Malformed(
        val errors: List<FieldError>,
    ) : IdentityError {
        init {
            require(errors.isNotEmpty()) { "a malformed input names at least one broken rule" }
        }
    }

    /** Unknown email or wrong password: the same answer for both (401, FR-006). */
    data object InvalidCredentials : IdentityError

    /** Correct credentials of an account whose email is not verified yet (403). */
    data object EmailNotVerified : IdentityError

    /** Too many consecutive failures, or a repeated request too soon (429 with `Retry-After`). */
    data class Throttled(
        val retryAfter: Duration,
    ) : IdentityError

    /** A verification or reset token that is unknown, used or expired (422 on field `token`). */
    data object InvalidToken : IdentityError

    /** A refresh token that is unknown, revoked, expired or already spent (401). */
    data object InvalidRefreshToken : IdentityError

    /** A spent refresh token was presented again: the session must be revoked (answered like [InvalidRefreshToken]). */
    data object RefreshTokenReused : IdentityError

    /** No live account with that id (401 for the caller's own account, 404 on the internal API). */
    data object AccountNotFound : IdentityError

    /** The address does not exist or belongs to another account (404). */
    data object AddressNotFound : IdentityError

    /** An account keeps at most [AddressBook.MAX_ADDRESSES] addresses (422). */
    data object TooManyAddresses : IdentityError

    /** Operator accounts cannot delete themselves (403). */
    data object OperatorCannotSelfDelete : IdentityError

    /** `sms` was chosen without a verified phone number (422). */
    data object SmsRequiresVerifiedPhone : IdentityError

    /** A phone code was confirmed while none is pending (422). */
    data object NoPendingPhoneVerification : IdentityError

    /** The phone code does not match (422; counts as an attempt). */
    data object WrongCode : IdentityError

    /** The phone code expired (422). */
    data object CodeExpired : IdentityError

    /** Every attempt of the phone code was used (422; a new code must be requested). */
    data object TooManyCodeAttempts : IdentityError

    /** No signing key is available, so no access token can be issued (503). */
    data object SigningUnavailable : IdentityError

    /** The SMS channel did not accept the code (503). */
    data object SmsUnavailable : IdentityError

    /** The account changed concurrently; the caller may retry (409). */
    data object ConcurrentUpdate : IdentityError
}
