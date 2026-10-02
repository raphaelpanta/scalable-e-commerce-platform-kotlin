package com.ecommerce.gateway.correlation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldHaveLength
import io.kotest.property.Arb
import io.kotest.property.arbitrary.pattern
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.uuid
import io.kotest.property.checkAll

private const val GENERATED = "00000000-0000-4000-8000-000000000000"

class CorrelationIdsTest :
    FunSpec({
        fun resolve(candidate: String?) = CorrelationIds.resolve(candidate) { GENERATED }

        test("a UUID is accepted as is") {
            checkAll(Arb.uuid()) { uuid ->
                resolve(uuid.toString()) shouldBe CorrelationIds.Resolution(uuid.toString(), original = null)
            }
        }

        test("a 16 to 64 character token of letters, digits and hyphens is accepted") {
            checkAll(Arb.pattern("[A-Za-z0-9-]{16,64}")) { token ->
                resolve(token).id shouldBe token
                resolve(token).replaced.shouldBeFalse()
            }
        }

        test("the length bounds are inclusive") {
            CorrelationIds.isAcceptable("a".repeat(16)).shouldBeTrue()
            CorrelationIds.isAcceptable("a".repeat(64)).shouldBeTrue()
            CorrelationIds.isAcceptable("a".repeat(15)).shouldBeFalse()
            CorrelationIds.isAcceptable("a".repeat(65)).shouldBeFalse()
        }

        test("a missing or empty value is generated and nothing is recorded") {
            resolve(null) shouldBe CorrelationIds.Resolution(GENERATED, original = null)
            resolve("") shouldBe CorrelationIds.Resolution(GENERATED, original = null)
        }

        test("a malformed value is replaced and kept for the access log") {
            listOf("abc-123", "has space in it 1234", "semi;colon-0123456789", "ünïcode-0123456789abcdef").forEach {
                resolve(it) shouldBe CorrelationIds.Resolution(GENERATED, original = it)
                resolve(it).replaced.shouldBeTrue()
            }
        }

        test("an oversized value is replaced and only its beginning is logged") {
            val oversized = "x".repeat(1000)
            val resolution = resolve(oversized)
            resolution.id shouldBe GENERATED
            resolution.original.shouldNotBeNull() shouldHaveLength CorrelationIds.MAX_LOGGED_ORIGINAL
        }

        test("anything outside the accepted alphabet is never accepted") {
            checkAll(Arb.string(1..80)) { candidate ->
                val acceptable = Regex("[A-Za-z0-9-]{16,64}").matches(candidate)
                CorrelationIds.isAcceptable(candidate) shouldBe acceptable
            }
        }

        test("generated ids are UUIDs") {
            CorrelationIds.resolve(null).original.shouldBeNull()
            CorrelationIds.isAcceptable(CorrelationIds.newId()).shouldBeTrue()
        }
    })
