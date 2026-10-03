-- The voided outcome (data-model section 3.5, conventions section 8): a pending attempt that will never be resolved,
-- because its order was cancelled (shopper, operator or payment expiry) or because a retry superseded it. Terminal,
-- published by no event, and like a pending attempt it carries no provider reference.
ALTER TABLE payment_attempts
    DROP CONSTRAINT payment_attempts_outcome_check,
    ADD CONSTRAINT payment_attempts_outcome_check
        CHECK (outcome IN ('approved', 'declined', 'pending', 'voided')),
    DROP CONSTRAINT payment_attempts_reference_unless_pending,
    ADD CONSTRAINT payment_attempts_reference_unless_unresolved
        CHECK ((provider_reference IS NULL) = (outcome IN ('pending', 'voided')));
