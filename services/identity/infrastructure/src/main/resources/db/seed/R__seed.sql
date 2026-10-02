-- Seed data of the identity service (profile `seed`, SEED=true; docs/service-conventions.md section 8): the operator
-- account operator@ecommerce.example with roles shopper and operator, email verified, and its default preferences.
-- Idempotent: rows that already exist (same id, or a live account with the same email) are left alone.
--
-- The password is Operator-Passw0rd!2026, stored as an Argon2id PHC string exactly as Argon2idPasswordHasher writes
-- it (m=19456 KiB, t=2, p=1, random 16-byte salt). To change it, generate a new string with
-- `Argon2idPasswordHasher().encode("<password>")` (or any RFC 9106 Argon2id implementation with the same parameters
-- and unpadded standard Base64), replace it below and keep OPERATOR_PASSWORD of the acceptance suite in step;
-- SeedScriptTest checks that the stored hash matches the documented password.

INSERT INTO account (id, email, password_hash, status, roles, display_name, created_at, verified_at, failed_sign_ins,
                     locked_until, deleted_at, pseudonym, version)
VALUES ('e5a1c3b7-2d40-4f9e-8b16-3c7d9a0f1e22',
        'operator@ecommerce.example',
        '$argon2id$v=19$m=19456,t=2,p=1$GaGUua2M7waoXRzJHusCdw$fPWxweOdR9gTwZsHl1IMveTNp67+wU9csPOh2KnTOxA',
        'active',
        'shopper,operator',
        'Platform Operator',
        '2026-10-02T00:00:00Z',
        '2026-10-02T00:00:00Z',
        0,
        NULL,
        NULL,
        NULL,
        0)
ON CONFLICT DO NOTHING;

INSERT INTO notification_preference (account_id, channels, phone_number, phone_verified)
SELECT id, 'email', NULL, false FROM account WHERE id = 'e5a1c3b7-2d40-4f9e-8b16-3c7d9a0f1e22'
ON CONFLICT DO NOTHING;
