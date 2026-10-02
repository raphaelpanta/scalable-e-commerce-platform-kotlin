package com.ecommerce.identity.domain

import java.util.UUID

/** Identifier of an account (the `sub` claim of its access tokens; data-model section 1: one typed id per concept). */
@JvmInline
value class AccountId(
    val value: UUID,
)

/** Identifier of a delivery address of an account. */
@JvmInline
value class AddressId(
    val value: UUID,
)

/** Identifier of a session (one sign-in and the refresh tokens rotated from it; the `sid` claim). */
@JvmInline
value class SessionId(
    val value: UUID,
)
