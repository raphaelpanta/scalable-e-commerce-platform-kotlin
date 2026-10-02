package com.ecommerce.identity.infrastructure

import com.ecommerce.platform.core.problem.ProblemType
import com.ecommerce.platform.testing.ProblemAssertions.expectProblem
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

private const val OPERATOR_EMAIL = "operator@ecommerce.example"
private const val OPERATOR_PASSWORD = "Operator-Passw0rd!2026"
private const val OPERATOR_ID = "e5a1c3b7-2d40-4f9e-8b16-3c7d9a0f1e22"

/**
 * T028 (identity half): with the profile `seed` (what `SEED=true` activates, and what [IdentityIntegrationTest]
 * runs with) Flyway applies `db/seed/R__seed.sql`, so the operator of docs/service-conventions.md section 8 signs in
 * with the documented password and holds both roles.
 */
class SeedProfileIT : IdentityIntegrationTest() {
    @Test
    fun `the seeded operator signs in with both roles and cannot delete itself`() {
        val operator = signedIn(TestAccount(OPERATOR_EMAIL, OPERATOR_PASSWORD))

        val profile = get(ME, operator.bearer).json(OK)
        profile["id"] shouldBe OPERATOR_ID
        profile["email"] shouldBe OPERATOR_EMAIL
        profile["roles"] shouldBe listOf("shopper", "operator")
        profile["emailVerified"] shouldBe true
        profile["displayName"] shouldBe "Platform Operator"

        delete(ME, operator.bearer).expectProblem(ProblemType.FORBIDDEN)
    }
}
