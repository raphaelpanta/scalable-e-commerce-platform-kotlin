package com.ecommerce.platform.core.values

import com.ecommerce.platform.core.result.ValidationError
import com.ecommerce.platform.testing.PlatformArbs
import com.ecommerce.platform.testing.ProblemAssertions.shouldBeValid
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll

private val EUR: Currency = Currency.of("EUR").shouldBeValid()
private val OUT_OF_RANGE = ValidationError("amountMinor", "is out of range")
private val MISMATCH = ValidationError("currency", "must be BRL, was EUR")

private fun brl(amount: Long): Money = Money.of(amount, Currency.BRL).shouldBeValid()

class MoneySpec :
    FunSpec({
        test("non-negative amounts are accepted as given") {
            checkAll(Arb.long(0L..Long.MAX_VALUE)) { amount ->
                val money = brl(amount)
                money.amountMinor shouldBe amount
                money.currency shouldBe Currency.BRL
                money.isNegative shouldBe false
            }
        }

        test("negative amounts are rejected, zero is the boundary") {
            Money.of(-1, Currency.BRL).leftOrNull() shouldBe ValidationError("amountMinor", "must not be negative")
            checkAll(Arb.long(Long.MIN_VALUE..-1L)) { amount ->
                Money.of(amount, Currency.BRL).isLeft() shouldBe true
            }
            brl(0).isZero shouldBe true
            brl(1).isZero shouldBe false
        }

        test("the currency code is validated") {
            Money.of(100, "BRL") shouldBe Money.of(100, Currency.BRL)
            Money.of(100, "brl").leftOrNull()?.field shouldBe "currency"
            Money.of(-1, "BRL").leftOrNull()?.field shouldBe "amountMinor"
        }

        test("plus adds amounts of the same currency") {
            checkAll(PlatformArbs.money(), PlatformArbs.money()) { a, b ->
                (a + b).shouldBeValid().amountMinor shouldBe a.amountMinor + b.amountMinor
            }
        }

        test("plus rejects another currency and overflow") {
            (brl(1) + Money.of(1, EUR).shouldBeValid()).leftOrNull() shouldBe MISMATCH
            (brl(Long.MAX_VALUE) + brl(1)).leftOrNull() shouldBe OUT_OF_RANGE
            (brl(Long.MAX_VALUE - 1) + brl(1)).shouldBeValid().amountMinor shouldBe Long.MAX_VALUE
        }

        test("minus subtracts down to zero and rejects a negative result") {
            checkAll(PlatformArbs.money(), PlatformArbs.money()) { a, b ->
                val (big, small) = if (a.amountMinor >= b.amountMinor) a to b else b to a
                (big - small).shouldBeValid().amountMinor shouldBe big.amountMinor - small.amountMinor
            }
            (brl(5) - brl(5)).shouldBeValid().isZero shouldBe true
            (brl(5) - brl(6)).leftOrNull() shouldBe ValidationError("amountMinor", "must not become negative")
            (brl(5) - Money.of(1, EUR).shouldBeValid()).leftOrNull() shouldBe MISMATCH
        }

        test("difference may become negative but not overflow") {
            val difference = brl(5).difference(brl(7)).shouldBeValid()
            difference.amountMinor shouldBe -2
            difference.isNegative shouldBe true
            Money.signed(Long.MIN_VALUE, Currency.BRL).difference(brl(1)).leftOrNull() shouldBe OUT_OF_RANGE
            brl(1).difference(Money.of(1, EUR).shouldBeValid()).leftOrNull() shouldBe MISMATCH
        }

        test("times multiplies by a non-negative factor") {
            checkAll(PlatformArbs.money(), Arb.int(0..1000)) { money, factor ->
                (money * factor).shouldBeValid().amountMinor shouldBe money.amountMinor * factor
            }
            (brl(7) * 0).shouldBeValid().isZero shouldBe true
            (brl(7) * -1).leftOrNull() shouldBe ValidationError("factor", "must not be negative")
            (brl(Long.MAX_VALUE) * 2).leftOrNull() shouldBe OUT_OF_RANGE
            (brl(1999) * Quantity.of(3).shouldBeValid()).shouldBeValid() shouldBe brl(5997)
        }

        test("signed amounts may be negative") {
            val refund = Money.signed(-1500, Currency.BRL)
            refund.amountMinor shouldBe -1500
            refund.isNegative shouldBe true
            (refund + brl(1500)).shouldBeValid().isZero shouldBe true
            (refund - brl(1)).leftOrNull() shouldBe ValidationError("amountMinor", "must not become negative")
            Money.signed(0, Currency.BRL).isNegative shouldBe false
        }

        test("sum folds amounts of one currency") {
            Money.sum(emptyList(), Currency.BRL) shouldBe Money.of(0, Currency.BRL)
            Money.sum(listOf(brl(1), brl(2), brl(3)), Currency.BRL).shouldBeValid() shouldBe brl(6)
            Money.sum(listOf(brl(1), Money.of(2, EUR).shouldBeValid()), Currency.BRL).leftOrNull() shouldBe MISMATCH
            Money.sum(listOf(brl(Long.MAX_VALUE), brl(1)), Currency.BRL).leftOrNull() shouldBe OUT_OF_RANGE
            Money.zero(EUR) shouldBe Money.of(0, EUR).shouldBeValid()
        }
    })

class CurrencySpec :
    FunSpec({
        test("known ISO-4217 codes are accepted") {
            listOf("BRL", "EUR", "USD", "JPY").forEach { code -> Currency.of(code).shouldBeValid().code shouldBe code }
            Currency.BRL.toString() shouldBe "BRL"
        }

        test("malformed and unknown codes are rejected with the field name") {
            val format = ValidationError("currency", "must be an ISO-4217 code of three upper-case letters")
            listOf("brl", "BR", "BRLL", "", "B1L").forEach { code -> Currency.of(code).leftOrNull() shouldBe format }
            Currency.of("ABC", "price.currency").leftOrNull() shouldBe
                ValidationError("price.currency", "is not a known ISO-4217 currency")
        }
    })
