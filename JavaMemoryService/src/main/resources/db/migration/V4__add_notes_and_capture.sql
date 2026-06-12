CREATE TABLE notes (
    id         BIGSERIAL PRIMARY KEY,
    text       TEXT         NOT NULL,
    tags       VARCHAR(500),
    source     VARCHAR(50)  NOT NULL DEFAULT 'agent',
    created_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_notes_created_at ON notes(created_at DESC);
