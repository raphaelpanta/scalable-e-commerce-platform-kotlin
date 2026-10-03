-- Indexes of the retention purge (data-model section 5, IdentityPurgeJob): every purge statement filters on one of
-- these time columns. sign_in_source now also counts sign-in failures of emails without an account (key
-- "sign-in-email:<email>", hashed like the source addresses), so that unknown addresses lock like accounts (FR-006).

CREATE INDEX one_time_token_by_expiry ON one_time_token (expires_at);

CREATE INDEX account_session_by_expiry ON account_session (expires_at);

CREATE INDEX account_session_by_revocation ON account_session (revoked_at) WHERE revoked_at IS NOT NULL;

CREATE INDEX sign_in_source_by_update ON sign_in_source (updated_at);

CREATE INDEX phone_verification_by_expiry ON phone_verification (expires_at);

COMMENT ON TABLE sign_in_source IS
    'Consecutive failed sign-ins per SHA-256 key: a source address, or an email that has no live account';
