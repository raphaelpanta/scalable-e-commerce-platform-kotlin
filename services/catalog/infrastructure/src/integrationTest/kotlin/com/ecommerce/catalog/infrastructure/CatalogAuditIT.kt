package com.ecommerce.catalog.infrastructure

import com.ecommerce.platform.core.problem.ProblemType
import com.ecommerce.platform.testing.ProblemAssertions.expectProblem
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

private const val UNPROCESSABLE = 422
private val RECENT: Duration = Duration.ofMinutes(1)

/**
 * The durable audit trail (T137, FR-002, US7 scenarios 2 and 4): `audit_entry` holds one append-only row per operator
 * change and per refused attempt, with the operator id, the action, the target, the outcome, the correlation id and
 * the time. The operator check comes before any validation (T159), so shoppers always get 403 and an entry.
 */
class CatalogAuditIT : CatalogIntegrationTest() {
    private fun entries(correlationId: String): List<Map<String, Any?>> =
        rows(
            "SELECT operator_id, action, target_type, target_id, outcome, correlation_id, at FROM audit_entry " +
                "WHERE correlation_id = :correlationId ORDER BY at, action",
            mapOf("correlationId" to correlationId),
        )

    /** The row of a change the test operator performed on [targetId] in the request [correlationId]. */
    private fun performed(
        action: String,
        targetType: String,
        targetId: Any?,
        correlationId: String,
    ): Map<String, Any?> =
        mapOf(
            "operator_id" to OPERATOR_ID,
            "action" to action,
            "target_type" to targetType,
            "target_id" to UUID.fromString(targetId.toString()),
            "outcome" to "performed",
            "correlation_id" to correlationId,
        )

    /**
     * One malformed request per operator operation: a malformed path, a missing body, a body of the wrong shape. An
     * operator gets 400; a shopper never learns which, it gets 403.
     */
    private fun malformed(productId: String): List<Triple<HttpMethod, String, Any?>> =
        listOf(
            Triple(HttpMethod.POST, "$PRODUCTS/not-a-uuid/withdrawal", null),
            Triple(HttpMethod.PUT, "$PRODUCTS/$productId", null),
            Triple(HttpMethod.POST, "$PRODUCTS/$productId/stock-adjustments", mapOf("reason" to "No delta")),
            Triple(HttpMethod.POST, "$PRODUCTS/$productId/images", mapOf("altText" to "No url")),
            Triple(HttpMethod.POST, PRODUCTS, mapOf("name" to "No price")),
            Triple(HttpMethod.POST, CATEGORIES, emptyMap<String, Any>()),
            Triple(HttpMethod.PUT, "$CATEGORIES/not-a-uuid", mapOf("name" to "x")),
            Triple(HttpMethod.POST, "$CATEGORIES/not-a-uuid/withdrawal", null),
            Triple(HttpMethod.POST, "$PRODUCTS/not-a-uuid/reinstatement", null),
            Triple(HttpMethod.POST, "$CATEGORIES/not-a-uuid/reinstatement", null),
        )

    @Test
    fun `every operator change is recorded with who, what, the request's correlation id and when`() {
        val correlationId = UUID.randomUUID().toString()
        val categoryId =
            call(HttpMethod.POST, CATEGORIES, operator(), mapOf("name" to "Audited $correlationId"), correlationId)
                .json(CREATED)["id"]
        val body =
            mapOf(
                "name" to "Audited product",
                "price" to mapOf("amountMinor" to PRICE, "currency" to "BRL"),
                "categoryId" to categoryId,
                "initialStock" to 1,
            )
        val productId = call(HttpMethod.POST, PRODUCTS, operator(), body, correlationId).json(CREATED)["id"]
        call(HttpMethod.POST, "$PRODUCTS/$productId/withdrawal", operator(), null, correlationId).json(OK)
        call(HttpMethod.POST, "$CATEGORIES/$categoryId/withdrawal", operator(), null, correlationId).json(OK)
        call(HttpMethod.POST, "$CATEGORIES/$categoryId/reinstatement", operator(), null, correlationId).json(OK)
        call(HttpMethod.POST, "$PRODUCTS/$productId/reinstatement", operator(), null, correlationId).json(OK)
        // A change refused for its content is not a change: no entry.
        call(HttpMethod.PUT, "$CATEGORIES/$categoryId", operator(), mapOf("name" to ""), correlationId)
            .expectProblem(ProblemType.VALIDATION, UNPROCESSABLE)

        val recorded = entries(correlationId)

        recorded.map { it - "at" } shouldContainExactly
            listOf(
                performed("createCategory", "category", categoryId, correlationId),
                performed("createProduct", "product", productId, correlationId),
                performed("withdrawProduct", "product", productId, correlationId),
                performed("withdrawCategory", "category", categoryId, correlationId),
                performed("reinstateCategory", "category", categoryId, correlationId),
                performed("reinstateProduct", "product", productId, correlationId),
            )
        recorded.forEach { row ->
            val at =
                when (val value = row["at"]) {
                    is OffsetDateTime -> value.toInstant()
                    else -> value as Instant
                }
            (Duration.between(at, Instant.now()).abs() < RECENT) shouldBe true
        }
    }

    @Test
    fun `malformed operator requests answer 400`() {
        malformed(product()).forEach { (method, uri, body) ->
            call(method, uri, operator(), body).expectProblem(ProblemType.VALIDATION, BAD_REQUEST)
        }
    }

    @Test
    fun `shoppers are refused before any validation, always with 403 and an audit entry`() {
        val correlationId = UUID.randomUUID().toString()
        val productId = product()
        val malformed = malformed(productId)

        malformed.forEach { (method, uri, body) ->
            call(method, uri, shopper(), body, correlationId).expectProblem(ProblemType.FORBIDDEN)
        }

        val recorded = entries(correlationId)
        recorded shouldHaveSize malformed.size
        recorded.forEach { row ->
            row["outcome"] shouldBe "refused"
            row["operator_id"] shouldBe SHOPPER_ID
        }
        recorded.map { it["action"] }.toSet() shouldBe
            setOf(
                "withdrawProduct",
                "updateProduct",
                "adjustStock",
                "addProductImage",
                "createProduct",
                "createCategory",
                "updateCategory",
                "withdrawCategory",
                "reinstateProduct",
                "reinstateCategory",
            )
        recorded.single { it["action"] == "withdrawProduct" }["target_id"] shouldBe null
        recorded.single { it["action"] == "updateProduct" }["target_id"] shouldBe UUID.fromString(productId)
        recorded.single { it["action"] == "createCategory" }["target_type"] shouldBe "category"
    }

    @Test
    fun `the audit trail is append-only`() {
        val correlationId = UUID.randomUUID().toString()
        call(HttpMethod.POST, CATEGORIES, shopper(), mapOf("name" to "x"), correlationId)
            .expectProblem(ProblemType.FORBIDDEN)
        entries(correlationId) shouldHaveSize 1

        val update =
            shouldThrowAny {
                execute(
                    "UPDATE audit_entry SET outcome = 'performed' WHERE correlation_id = :id",
                    mapOf("id" to correlationId),
                )
            }
        val delete =
            shouldThrowAny {
                execute("DELETE FROM audit_entry WHERE correlation_id = :id", mapOf("id" to correlationId))
            }

        update.stackTraceToString() shouldContain "append-only"
        delete.stackTraceToString() shouldContain "append-only"
        entries(correlationId).single()["outcome"] shouldBe "refused"
    }

    private companion object {
        const val OK = 200
        const val CREATED = 201
        const val BAD_REQUEST = 400
        const val PRICE = 990
    }
}
