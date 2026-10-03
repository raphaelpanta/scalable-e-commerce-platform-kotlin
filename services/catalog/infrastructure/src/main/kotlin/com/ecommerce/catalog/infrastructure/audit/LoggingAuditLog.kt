package com.ecommerce.catalog.infrastructure.audit

import com.ecommerce.catalog.application.AuditEntry
import com.ecommerce.catalog.application.AuditLog
import com.ecommerce.catalog.application.AuditOutcome
import com.ecommerce.platform.correlation.CorrelationIds
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.Locale

/**
 * Outbound adapter: the audit trail of operator capabilities as structured log lines (US7 scenario 4, FR-002) under
 * `audit.*`: the outcome, the action, the target type and id, the (pseudonymous) account id and the request's
 * correlation id. No personal data is ever logged: no names, emails, tokens or request bodies. [R2dbcAuditLog] keeps
 * the durable copy and delegates the log line here.
 */
class LoggingAuditLog : AuditLog {
    override suspend fun record(entry: AuditEntry) = log(entry, CorrelationIds.current())

    /** Logs [entry] with the [correlationId] of its request. */
    fun log(
        entry: AuditEntry,
        correlationId: String?,
    ) {
        val refused = entry.outcome == AuditOutcome.REFUSED
        val event = if (refused) log.atWarn() else log.atInfo()
        val message = if (refused) REFUSED else PERFORMED
        event
            .addKeyValue("audit.outcome", entry.outcome.lower())
            .addKeyValue("audit.action", entry.action.operationId)
            .addKeyValue("audit.targetType", entry.action.target.lower())
            .addKeyValue("audit.target", entry.targetId?.toString())
            .addKeyValue("audit.accountId", entry.actorId?.value?.toString())
            .addKeyValue("audit.correlationId", correlationId)
            .log(message, entry.action.operationId)
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger("com.ecommerce.catalog.audit")
        const val REFUSED = "Operator capability {} refused: the caller lacks the operator role"
        const val PERFORMED = "Operator capability {} performed"

        fun Enum<*>.lower(): String = name.lowercase(Locale.ROOT)
    }
}
