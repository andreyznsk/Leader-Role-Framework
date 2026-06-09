CREATE TABLE captures (
    id           BIGSERIAL PRIMARY KEY,
    raw_text     TEXT        NOT NULL,
    source       VARCHAR(50) NOT NULL DEFAULT 'cli',
    status       VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    classified   VARCHAR(20),
    routed_to    VARCHAR(100),
    captured_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
    processed_at TIMESTAMP
);

CREATE TABLE questions (
    id         BIGSERIAL    PRIMARY KEY,
    title      VARCHAR(500) NOT NULL,
    context    TEXT,
    status     VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    created_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE person_notes (
    id          BIGSERIAL    PRIMARY KEY,
    person_name VARCHAR(100) NOT NULL,
    note        TEXT         NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_captures_status     ON captures(status);
CREATE INDEX idx_captures_captured_at ON captures(captured_at);
CREATE INDEX idx_questions_status    ON questions(status);
CREATE INDEX idx_person_notes_name   ON person_notes(person_name);
