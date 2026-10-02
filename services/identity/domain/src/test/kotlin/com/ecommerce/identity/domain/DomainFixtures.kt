package com.ecommerce.identity.domain

import arrow.core.Either
import io.kotest.assertions.fail
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.pattern
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.uuid
import java.time.Instant
import java.util.UUID

val NOW: Instant = Instant.parse("2026-10-02T10:00:00Z")

fun <E, T> Either<E, T>.value(): T = fold({ fail("expected a value but was $it") }, { it })

fun <E, T> Either<E, T>.error(): E = fold({ it }, { fail("expected an error but was $it") })

fun email(raw: String = "ada@example.test"): Email = Email.of(raw).value()

fun phone(raw: String = "+5511987654321"): PhoneNumber = PhoneNumber.of(raw).value()

fun accountId(): AccountId = AccountId(UUID.randomUUID())

fun token(seed: Char = 'a'): OpaqueToken = OpaqueToken.of(seed.toString().repeat(OpaqueToken.LENGTH)).value()

fun code(raw: String = "123456"): VerificationCode = VerificationCode.of(raw).value()

fun account(
    id: AccountId = accountId(),
    status: AccountStatus = AccountStatus.ACTIVE,
    roles: Set<Role> = setOf(Role.SHOPPER),
): Account =
    Account
        .register(id, email(), PasswordHash("\$argon2id\$hash"), DisplayName.of("Ada").value(), NOW)
        .copy(status = status, roles = roles, verifiedAt = if (status == AccountStatus.UNVERIFIED) null else NOW)

@Suppress("LongParameterList") // one parameter per postal field
fun fields(
    recipientName: String? = "Ada Lovelace",
    line1: String? = "12 Analytical Street",
    line2: String? = "Flat 2",
    city: String? = "London",
    region: String? = "England",
    postalCode: String? = "N1 9GU",
    countryCode: String? = "GB",
): PostalAddress.Fields = PostalAddress.Fields(recipientName, line1, line2, city, region, postalCode, countryCode)

fun draft(
    isDefault: Boolean = false,
    label: String? = "Home",
): AddressDraft = AddressDraft.of(label, fields(), isDefault).value()

val arbAccountId: Arb<AccountId> = Arb.uuid().map(::AccountId)

val arbSeconds: Arb<Long> = Arb.long(0L..100_000L)

val arbLocal: Arb<String> = Arb.pattern("[a-z0-9._%+-]{1,20}")

val arbEmail: Arb<String> = arbitrary { "${arbLocal.bind()}@example.test" }

val arbFailures: Arb<Int> = Arb.int(0..20)

val arbText: Arb<String> = Arb.string(0..200)
