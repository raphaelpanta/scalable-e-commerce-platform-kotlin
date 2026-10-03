package com.ecommerce.catalog.infrastructure.audit

import com.ecommerce.catalog.application.AuditEntry
import com.ecommerce.catalog.application.AuditLog
import com.ecommerce.catalog.infrastructure.persistence.bindNullable
import com.ecommerce.catalog.infrastructure.persistence.column
import com.ecommerce.platform.correlation.CorrelationIds
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import java.util.UUID

/**
 * Outbound adapter: the durable, append-only audit trail in `audit_entry` (V6__catalog_audit_entries.sql), with the
 * request's correlation id, followed by the structured log line of [lines]. Entries of performed changes are written
 * inside the change's transaction (the statement joins it), so a rolled-back change leaves no entry; refused attempts
 * are written on their own. A failure to store an entry fails the request: an operator change is never unaudited.
 */
class R2dbcAuditLog(
    private val database: DatabaseClient,
    private val lines: LoggingAuditLog = LoggingAuditLog(),
) : AuditLog {
    override suspend fun record(entry: AuditEntry) {
        val correlationId = CorrelationIds.current()?.take(CORRELATION_MAX)
        database
            .sql(
                "INSERT INTO audit_entry (id, operator_id, action, target_type, target_id, outcome, correlation_id, " +
                    "at) VALUES (:id, :operatorId, :action, :targetType, :targetId, :outcome, :correlationId, :at)",
            ).bind("id", entry.id)
            .bindNullable("operatorId", entry.actorId?.value, UUID::class.java)
            .bind("action", entry.action.operationId)
            .bind("targetType", entry.action.target.column())
            .bindNullable("targetId", entry.targetId, UUID::class.java)
            .bind("outcome", entry.outcome.column())
            .bindNullable("correlationId", correlationId, String::class.java)
            .bind("at", entry.at)
            .fetch()
            .awaitRowsUpdated()
        lines.log(entry, correlationId)
    }

    private companion object {
        const val CORRELATION_MAX = 64
    }
}
