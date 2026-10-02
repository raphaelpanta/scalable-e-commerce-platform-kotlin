package com.ecommerce.catalog.infrastructure.audit

import com.ecommerce.catalog.application.AuditLog
import com.ecommerce.catalog.application.Caller
import com.ecommerce.catalog.domain.AccountId
import com.ecommerce.platform.correlation.CorrelationIds
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.spi.LoggingEventBuilder
import java.util.UUID

/**
 * Outbound adapter: the audit trail of operator capabilities as structured log lines (US7 scenario 4, FR-002) under
 * `audit.*`: the outcome, the action, the target id, the (pseudonymous) account id and the request's correlation id.
 * No personal data is ever logged: no names, emails, tokens or request bodies.
 */
class LoggingAuditLog : AuditLog {
    override suspend fun refused(
        caller: Caller,
        action: String,
        target: UUID?,
    ) {
        log
            .atWarn()
            .audit("refused", action, target, caller.accountId?.value)
            .log("Operator capability {} refused: the caller lacks the operator role", action)
    }

    override suspend fun changed(
        actor: AccountId,
        action: String,
        target: UUID,
    ) {
        log
            .atInfo()
            .audit("performed", action, target, actor.value)
            .log("Operator capability {} performed", action)
    }

    private suspend fun LoggingEventBuilder.audit(
        outcome: String,
        action: String,
        target: UUID?,
        accountId: UUID?,
    ): LoggingEventBuilder =
        addKeyValue("audit.outcome", outcome)
            .addKeyValue("audit.action", action)
            .addKeyValue("audit.target", target?.toString())
            .addKeyValue("audit.accountId", accountId?.toString())
            .addKeyValue("audit.correlationId", CorrelationIds.current())

    private companion object {
        val log: Logger = LoggerFactory.getLogger("com.ecommerce.catalog.audit")
    }
}
