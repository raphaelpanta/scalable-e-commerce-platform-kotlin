package com.ecommerce.platform.core.result

import arrow.core.left
import arrow.core.nonEmptyListOf
import arrow.core.right
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun ok(value: Int): Validated<Int> = value.right()

private fun bad(field: String): Validated<Int> = ValidationError(field, "is wrong").left()

class ValidationSpec :
    FunSpec({
        test("ensureThat") {
            ensureThat(true, "f") { "never" } shouldBe Unit.right()
            ensureThat(false, "f") { "is wrong" } shouldBe ValidationError("f", "is wrong").left()
        }

        test("accumulating lifts one error into a non-empty list") {
            bad("a").accumulating() shouldBe nonEmptyListOf(ValidationError("a", "is wrong")).left()
            ok(1).accumulating() shouldBe 1.right()
        }

        test("validateAll combines every arity and keeps every error in order") {
            validateAll(ok(1), ok(2)) { a, b -> a + b } shouldBe 3.right()
            validateAll(ok(1), ok(2), ok(3)) { a, b, c -> a + b + c } shouldBe 6.right()
            validateAll(ok(1), ok(2), ok(3), ok(4)) { a, b, c, d -> a + b + c + d } shouldBe 10.right()
            validateAll(ok(1), ok(2), ok(3), ok(4), ok(5)) { a, b, c, d, e -> a + b + c + d + e } shouldBe 15.right()
            validateAll(ok(1), ok(2), ok(3), ok(4), ok(5), ok(6)) { a, b, c, d, e, f -> a + b + c + d + e + f } shouldBe
                21.right()
            validateAll(ok(1), ok(2), ok(3), ok(4), ok(5), ok(6), ok(7)) { a, b, c, d, e, f, g ->
                a + b + c + d + e + f + g
            } shouldBe 28.right()
            validateAll(bad("a"), ok(2), bad("c")) { a, b, c -> a + b + c }.leftOrNull()?.map { it.field } shouldBe
                listOf("a", "c")
        }
    })
