-- Bounded retry of pending charges (data-model section 3.5, US4 scenario 7): a retry is attempt N + 1 of the same
-- order, linked to the attempt it retries, which is voided when the retry is stored. The checkout Idempotency-Key
-- stays on the first attempt only: a retry is keyed by a key derived from the previous attempt's, so
-- payment_attempts_idempotency_key_unique still holds.
ALTER TABLE payment_attempts
    ADD COLUMN attempt_number      integer NOT NULL DEFAULT 1 CHECK (attempt_number >= 1),
    ADD COLUMN previous_attempt_id uuid    NULL REFERENCES payment_attempts (id),
    ADD CONSTRAINT payment_attempts_retry_links_previous
        CHECK ((previous_attempt_id IS NULL) = (attempt_number = 1)),
    -- One retry per attempt: two instances of the retry job never both retry the same attempt.
    ADD CONSTRAINT payment_attempts_one_retry_per_attempt UNIQUE (previous_attempt_id);

-- The retry job's scan: pending attempts, oldest first.
CREATE INDEX payment_attempts_pending_idx ON payment_attempts (created_at) WHERE outcome = 'pending';
