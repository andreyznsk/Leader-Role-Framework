ALTER TABLE mailagent.processed_emails
    ADD COLUMN IF NOT EXISTS output_path TEXT;
