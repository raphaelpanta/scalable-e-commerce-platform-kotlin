package com.ecommerce.catalog.application

import com.ecommerce.catalog.domain.AccountId
import java.time.Instant
import java.util.UUID

/** The kind of record an operator action targets. */
enum class AuditTarget {
    PRODUCT,
    CATEGORY,
}

/** Every operator capability of catalog.yaml, named by its [operationId], with the kind of record it changes. */
enum class OperatorAction(
    val operationId: String,
    val target: AuditTarget,
) {
    CREATE_PRODUCT("createProduct", AuditTarget.PRODUCT),
    UPDATE_PRODUCT("updateProduct", AuditTarget.PRODUCT),
    WITHDRAW_PRODUCT("withdrawProduct", AuditTarget.PRODUCT),
    ADJUST_STOCK("adjustStock", AuditTarget.PRODUCT),
    ADD_PRODUCT_IMAGE("addProductImage", AuditTarget.PRODUCT),
    CREATE_CATEGORY("createCategory", AuditTarget.CATEGORY),
    UPDATE_CATEGORY("updateCategory", AuditTarget.CATEGORY),
    WITHDRAW_CATEGORY("withdrawCategory", AuditTarget.CATEGORY),
}

/** Whether the operator capability was performed or refused for lack of the operator role. */
enum class AuditOutcome {
    PERFORMED,
    REFUSED,
}

/**
 * One append-only audit record (data-model section 3.2 `AuditEntry`, US7 scenarios 2 and 4): who ([actorId], null
 * for an anonymous caller), what ([action] on [targetId], null when the record does not exist yet), the [outcome] and
 * when. Account ids are pseudonymous; no personal data is recorded. The adapter adds the request's correlation id.
 */
data class AuditEntry(
    val id: UUID,
    val actorId: AccountId?,
    val action: OperatorAction,
    val targetId: UUID?,
    val outcome: AuditOutcome,
    val at: Instant,
)
