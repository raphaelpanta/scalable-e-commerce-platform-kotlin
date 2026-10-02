package com.ecommerce.identity.infrastructure

import com.ecommerce.identity.domain.PasswordHash
import com.ecommerce.identity.infrastructure.security.Argon2idPasswordHasher
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** The seed script (T028, identity half) and the `SEED` switch of the `seed` profile. */
class SeedScriptSpec :
    FunSpec({
        val script =
            checkNotNull(SeedScriptSpec::class.java.getResource("/db/seed/R__seed.sql")) { "seed script" }.readText()

        test("the seeded operator hash is the documented password (docs/service-conventions.md section 8)") {
            val hash =
                Regex("""'(\${'$'}argon2id\${'$'}[^']+)'""")
                    .find(script)
                    ?.groupValues
                    ?.get(1)
                    .shouldNotBeNull()

            Argon2idPasswordHasher().verify("Operator-Passw0rd!2026", PasswordHash(hash)) shouldBe true
            Argon2idPasswordHasher().verify("operator-passw0rd!2026", PasswordHash(hash)) shouldBe false
        }

        test("the operator is an active, verified shopper and operator") {
            script shouldContain "'operator@ecommerce.example'"
            script shouldContain "'shopper,operator'"
            script shouldContain "'active'"
            script shouldContain "ON CONFLICT DO NOTHING"
        }

        test("SEED=true activates the seed profile, anything else does not") {
            SeedProfile.profilesFor("true").toList() shouldBe listOf("seed")
            SeedProfile.profilesFor("TRUE").toList() shouldBe listOf("seed")
            SeedProfile.profilesFor("false").toList() shouldBe emptyList()
            SeedProfile.profilesFor(null).toList() shouldBe emptyList()
        }
    })
