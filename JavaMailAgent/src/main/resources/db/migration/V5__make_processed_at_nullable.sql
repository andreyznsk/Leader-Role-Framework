-- processed_at is set only when processing completes (withProcessed).
-- During intermediate states (PROCESSING, ERROR) the row has null processed_at.
ALTER TABLE mailagent.processed_emails
    ALTER COLUMN processed_at DROP NOT NULL;
