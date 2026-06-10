CREATE TABLE IF NOT EXISTS mailagent.processed_emails (
    id            BIGSERIAL PRIMARY KEY,
    email_id      VARCHAR(512) NOT NULL UNIQUE,
    folder        VARCHAR(255),
    sender        VARCHAR(255),
    subject       VARCHAR(512),
    agent_type    VARCHAR(16) NOT NULL,
    processed_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_processed_emails_email_id ON mailagent.processed_emails(email_id);
CREATE INDEX idx_processed_emails_processed_at ON mailagent.processed_emails(processed_at);
