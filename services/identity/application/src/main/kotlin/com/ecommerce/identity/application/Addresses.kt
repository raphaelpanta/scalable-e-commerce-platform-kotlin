package com.ecommerce.identity.application

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
import com.ecommerce.identity.domain.AccountId
import com.ecommerce.identity.domain.Address
import com.ecommerce.identity.domain.AddressDraft
import com.ecommerce.identity.domain.AddressId
import com.ecommerce.identity.domain.IdentityError
import com.ecommerce.identity.domain.PostalAddress

/** An address input as received (identity.yaml `AddressInput`). Personal data: `toString()` hides it. */
data class AddressInput(
    val label: String?,
    val fields: PostalAddress.Fields,
    val isDefault: Boolean,
) {
    override fun toString(): String = "AddressInput(isDefault=$isDefault)"

    fun draft(): Either<IdentityError, AddressDraft> = AddressDraft.of(label, fields, isDefault)
}

/** One page of addresses and the total count. */
data class AddressPage(
    val items: List<Address>,
    val totalItems: Long,
)

/** `listOwnAddresses`: a page of the caller's addresses, in creation order. */
class ListAddresses(
    private val store: IdentityStore,
) {
    suspend operator fun invoke(
        accountId: AccountId,
        offset: Long,
        limit: Int,
    ): Either<IdentityError, AddressPage> =
        either {
            store.liveAccount(accountId).bind()
            val book = store.addresses.book(accountId)
            AddressPage(book.page(offset, limit), book.addresses.size.toLong())
        }
}

/** `addOwnAddress`: at most 10 addresses; a new default replaces the previous one. */
class AddAddress(
    private val store: IdentityStore,
) {
    suspend operator fun invoke(
        accountId: AccountId,
        input: AddressInput,
    ): Either<IdentityError, Address> =
        either {
            val draft = input.draft().bind()
            store.liveAccount(accountId).bind()
            val id = AddressId(store.secrets.newId())
            val book =
                store.addresses
                    .book(accountId)
                    .add(id, draft)
                    .bind()
            store.addresses.save(book)
            ensureNotNull(book.find(id)) { IdentityError.AddressNotFound }
        }
}

/** `updateOwnAddress`: replaces an address of the caller; another account's address is not found. */
class ReplaceAddress(
    private val store: IdentityStore,
) {
    suspend operator fun invoke(
        accountId: AccountId,
        addressId: AddressId,
        input: AddressInput,
    ): Either<IdentityError, Address> =
        either {
            val draft = input.draft().bind()
            store.liveAccount(accountId).bind()
            val book =
                store.addresses
                    .book(accountId)
                    .replace(addressId, draft)
                    .bind()
            store.addresses.save(book)
            ensureNotNull(book.find(addressId)) { IdentityError.AddressNotFound }
        }
}

/** `deleteOwnAddress`: removes an address of the caller (orders keep their frozen copy). */
class RemoveAddress(
    private val store: IdentityStore,
) {
    suspend operator fun invoke(
        accountId: AccountId,
        addressId: AddressId,
    ): Either<IdentityError, Unit> =
        either {
            store.liveAccount(accountId).bind()
            store.addresses.save(
                store.addresses
                    .book(accountId)
                    .remove(addressId)
                    .bind(),
            )
        }
}

/**
 * `getAccountAddress` (internal, order at checkout): the postal fields of an address of a live account. Unknown
 * addresses, addresses of another account and addresses of deleted accounts are all [IdentityError.AddressNotFound].
 */
class GetAccountAddress(
    private val store: IdentityStore,
) {
    suspend operator fun invoke(
        accountId: AccountId,
        addressId: AddressId,
    ): Either<IdentityError, PostalAddress> =
        either {
            store.liveAccount(accountId).mapLeft { IdentityError.AddressNotFound }.bind()
            ensureNotNull(store.addresses.book(accountId).find(addressId)) { IdentityError.AddressNotFound }.postal
        }
}
