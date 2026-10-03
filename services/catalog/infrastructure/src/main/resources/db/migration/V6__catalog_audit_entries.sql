-- Durable audit trail of operator capabilities (FR-002, US7 scenarios 2 and 4, data-model section 3.2 AuditEntry):
-- one row per performed catalogue change (written in the change's transaction) and per refused attempt. Append-only
-- and kept indefinitely. operator_id is the caller's account id (pseudonymous, null for an anonymous caller); no
-- personal data, token or request body is stored.

CREATE TABLE audit_entry (
    id             uuid PRIMARY KEY,
    operator_id    uuid,
    action         varchar(40)  NOT NULL,
    target_type    varchar(16)  NOT NULL CHECK (target_type IN ('product', 'category')),
    target_id      uuid,
    outcome        varchar(16)  NOT NULL CHECK (outcome IN ('performed', 'refused')),
    correlation_id varchar(64),
    at             timestamptz  NOT NULL
);

CREATE INDEX audit_entry_target ON audit_entry (target_type, target_id, at);
CREATE INDEX audit_entry_operator ON audit_entry (operator_id, at);

-- Append-only: rows can be inserted, never changed or removed.
CREATE FUNCTION audit_entry_append_only() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'audit_entry is append-only';
END;
$$;

CREATE TRIGGER audit_entry_no_change
    BEFORE UPDATE OR DELETE ON audit_entry
    FOR EACH ROW EXECUTE FUNCTION audit_entry_append_only();
