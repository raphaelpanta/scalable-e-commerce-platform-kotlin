-- T158: a notification queued while identity could not tell its contact (and the event carried no snapshot) waits
-- for the delivery job to resolve its recipient before the first send (FR-018, FR-019).
ALTER TABLE notifications ADD COLUMN awaiting_recipient boolean NOT NULL DEFAULT false;

-- T140: the retention purge (data-model section 5, FR-007, NotificationPurgeJob) deletes terminal notifications by
-- age; their attempt history goes with them.
CREATE INDEX notifications_retention_idx ON notifications (created_at) WHERE status <> 'queued';

ALTER TABLE delivery_attempts DROP CONSTRAINT delivery_attempts_notification_id_fkey;
ALTER TABLE delivery_attempts
    ADD CONSTRAINT delivery_attempts_notification_id_fkey
        FOREIGN KEY (notification_id) REFERENCES notifications (id) ON DELETE CASCADE;
