package com.ecommerce.payment.domain

import arrow.core.left
import arrow.core.right
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import java.util.UUID

private const val TOO_LONG_TOKEN = 129
private const val TOO_LONG_REFERENCE = 65

/** Validation of the payment value objects (data-model section 2). */
class ValuesSpec :
    FunSpec({
        test("money is never negative and has an ISO-4217 code") {
            checkAll(
                Arb.long(Long.MIN_VALUE..-1L),
            ) { negative -> shouldThrow<IllegalArgumentException> { money(negative) } }
            money(0).amountMinor shouldBe 0
            listOf("brl", "BR", "BRLX", "B1L", "").forEach { code ->
                shouldThrow<IllegalArgumentException> { Money(1, code) }
            }
        }

        test("charge and refund amounts are strictly positive with a valid currency") {
            checkAll(Arb.long(1L..Long.MAX_VALUE)) { amount ->
                Money.positive(amount, BRL).getOrNull() shouldBe
                    money(amount)
            }
            checkAll(Arb.long(Long.MIN_VALUE..0L)) { amount ->
                Money.positive(amount, BRL) shouldBe
                    PaymentError.Invalid("amount.amountMinor", "must be at least 1").left()
            }
            Money.positive(1, "brl", "total") shouldBe
                PaymentError.Invalid("total.currency", "must be an ISO-4217 code").left()
            Money.positive(1, BRL).getOrNull() shouldBe money(1)
        }

        test("a payment method reference is an opaque 1..128 character token that never shows in logs") {
            checkAll(Arb.string(1..PaymentMethodRef.MAX_LENGTH)) { raw ->
                val ref = token(raw)
                ref.token shouldBe raw
                ref.toString() shouldBe "PaymentMethodRef(***)"
            }
            PaymentMethodRef.of("") shouldBe
                PaymentError.Invalid("paymentMethodRef", "must be 1 to 128 characters").left()
            PaymentMethodRef.of("a".repeat(TOO_LONG_TOKEN)).isLeft() shouldBe true
            PaymentMethodRef.of("a".repeat(PaymentMethodRef.MAX_LENGTH)).isRight() shouldBe true
            token(APPROVE_TOKEN).toString() shouldNotContain APPROVE_TOKEN
        }

        test("a provider reference has 1..64 characters without spaces") {
            ProviderReference("x").value shouldBe "x"
            ProviderReference("a".repeat(ProviderReference.MAX_LENGTH)).value.length shouldBe
                ProviderReference.MAX_LENGTH
            shouldThrow<IllegalArgumentException> { ProviderReference("") }
            shouldThrow<IllegalArgumentException> { ProviderReference("a".repeat(TOO_LONG_REFERENCE)) }
            shouldThrow<IllegalArgumentException> { ProviderReference("sim ch") }
        }

        test("paging is 0-based with 1..100 items per page") {
            checkAll(Arb.int(0..1_000), Arb.int(1..PageRequest.MAX_SIZE)) { page, size ->
                val request = PageRequest.of(page, size).getOrNull()
                request?.page shouldBe page
                request?.size shouldBe size
                request?.offset shouldBe page.toLong() * size
            }
            PageRequest.of(-1, 1) shouldBe PaymentError.Invalid("page", "must be 0 or more").left()
            PageRequest.of(0, 0) shouldBe PaymentError.Invalid("size", "must be between 1 and 100").left()
            PageRequest.of(0, PageRequest.MAX_SIZE + 1).isLeft() shouldBe true
            PageRequest.DEFAULT_SIZE shouldBe 20
        }

        test("operators see every payment, shoppers only their own") {
            val owner = AccountId(UUID.randomUUID())
            val stranger = AccountId(UUID.randomUUID())
            Caller(owner, setOf(Role.SHOPPER)).canSee(owner) shouldBe true
            Caller(stranger, setOf(Role.SHOPPER)).canSee(owner) shouldBe false
            Caller(stranger, setOf(Role.OPERATOR)).canSee(owner) shouldBe true
            Caller(owner, emptySet()).canSee(owner) shouldBe false
            Caller(owner, setOf(Role.OPERATOR)).isOperator shouldBe true
            Caller(owner, setOf(Role.SHOPPER)).isOperator shouldBe false
        }

        test("an order's payments are listed for its owner and operators, an order without payments for anyone") {
            val owner = AccountId(UUID.randomUUID())
            val stranger = Caller(AccountId(UUID.randomUUID()), setOf(Role.SHOPPER))
            Caller(owner, setOf(Role.SHOPPER)).canList(owner) shouldBe Unit.right()
            Caller(owner, setOf(Role.OPERATOR)).canList(AccountId(UUID.randomUUID())) shouldBe Unit.right()
            stranger.canList(null) shouldBe Unit.right()
            stranger.canList(owner) shouldBe PaymentError.NotFound.left()
        }

        test("a recipient never shows its contact data") {
            val recipient =
                Recipient(AccountId(UUID.randomUUID()), "ada@example.test", "+5511999990000", listOf("email"))
            recipient.toString() shouldNotContain "ada@example.test"
            recipient.toString() shouldNotContain "+5511999990000"
        }
    })
