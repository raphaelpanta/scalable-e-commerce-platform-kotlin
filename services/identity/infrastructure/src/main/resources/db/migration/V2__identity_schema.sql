-- Identity schema (data-model section 3.1). Secrets are stored as SHA-256 hashes only (tokens, phone codes, source
-- addresses); passwords as Argon2id PHC strings. Accounts are never deleted: deletion anonymises the row (FR-007).

CREATE TABLE account (
    id                uuid PRIMARY KEY,
    -- Lower-cased; the placeholder <pseudonym>@anonymised.invalid once the account is deleted.
    email             varchar(254) NOT NULL,
    password_hash     text,
    status            varchar(16)  NOT NULL CHECK (status IN ('unverified', 'active', 'deleted')),
    -- Comma-separated role codes (shopper, operator); never empty.
    roles             varchar(64)  NOT NULL CHECK (roles <> ''),
    display_name      varchar(100),
    created_at        timestamptz  NOT NULL,
    verified_at       timestamptz,
    failed_sign_ins   integer      NOT NULL DEFAULT 0 CHECK (failed_sign_ins >= 0),
    locked_until      timestamptz,
    deleted_at        timestamptz,
    pseudonym         varchar(64),
    version           bigint       NOT NULL
);

-- An email belongs to at most one live account.
CREATE UNIQUE INDEX account_live_email ON account (email) WHERE status <> 'deleted';

CREATE TABLE address (
    id              uuid PRIMARY KEY,
    account_id      uuid         NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    position        integer      NOT NULL,
    label           varchar(50),
    recipient_name  varchar(100) NOT NULL,
    line1           varchar(120) NOT NULL,
    line2           varchar(120),
    city            varchar(80)  NOT NULL,
    region          varchar(80),
    postal_code     varchar(20)  NOT NULL,
    country_code    char(2)      NOT NULL,
    is_default      boolean      NOT NULL
);

CREATE INDEX address_by_account ON address (account_id, position);

CREATE TABLE notification_preference (
    account_id      uuid PRIMARY KEY REFERENCES account (id) ON DELETE CASCADE,
    -- Comma-separated channel codes (email, sms).
    channels        varchar(32)  NOT NULL,
    phone_number    varchar(16),
    phone_verified  boolean      NOT NULL
);

CREATE TABLE phone_verification (
    account_id      uuid PRIMARY KEY REFERENCES account (id) ON DELETE CASCADE,
    phone_number    varchar(16)  NOT NULL,
    code_hash       char(64)     NOT NULL,
    issued_at       timestamptz  NOT NULL,
    expires_at      timestamptz  NOT NULL,
    attempts        integer      NOT NULL CHECK (attempts >= 0)
);

-- Verification (24 h) and password-reset (1 h) tokens, single use.
CREATE TABLE one_time_token (
    token_hash      char(64) PRIMARY KEY,
    account_id      uuid         NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    purpose         varchar(32)  NOT NULL CHECK (purpose IN ('email_verification', 'password_reset')),
    issued_at       timestamptz  NOT NULL,
    expires_at      timestamptz  NOT NULL,
    used_at         timestamptz
);

CREATE INDEX one_time_token_by_account ON one_time_token (account_id, purpose) WHERE used_at IS NULL;

CREATE TABLE account_session (
    id                  uuid PRIMARY KEY,
    account_id          uuid         NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    refresh_token_hash  char(64)     NOT NULL,
    issued_at           timestamptz  NOT NULL,
    expires_at          timestamptz  NOT NULL,
    rotated_at          timestamptz,
    revoked_at          timestamptz
);

CREATE INDEX account_session_by_account ON account_session (account_id);

-- Every refresh token a session handed out (current and spent), so a spent one presented again is recognised.
CREATE TABLE session_refresh_token (
    token_hash  char(64) PRIMARY KEY,
    session_id  uuid NOT NULL REFERENCES account_session (id) ON DELETE CASCADE
);

-- Consecutive failed sign-ins per source address (SHA-256 of the address).
CREATE TABLE sign_in_source (
    source_hash   char(64) PRIMARY KEY,
    failures      integer     NOT NULL CHECK (failures >= 0),
    locked_until  timestamptz,
    updated_at    timestamptz NOT NULL
);
