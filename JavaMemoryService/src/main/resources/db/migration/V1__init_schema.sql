CREATE TABLE daily_plans (
    id         BIGSERIAL PRIMARY KEY,
    plan_date  DATE         NOT NULL UNIQUE,
    summary    TEXT,
    status     VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE tasks (
    id          BIGSERIAL PRIMARY KEY,
    plan_id     BIGINT       REFERENCES daily_plans(id) ON DELETE CASCADE,
    title       VARCHAR(500) NOT NULL,
    description TEXT,
    status      VARCHAR(20)  NOT NULL DEFAULT 'TODO',
    priority    VARCHAR(10)  NOT NULL DEFAULT 'NORMAL',
    due_date    DATE,
    source      VARCHAR(20)  NOT NULL DEFAULT 'MANUAL',
    email_id    VARCHAR(500),
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE incidents (
    id           BIGSERIAL PRIMARY KEY,
    title        VARCHAR(500) NOT NULL,
    severity     VARCHAR(10)  NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    description  TEXT,
    root_cause   TEXT,
    action_items TEXT,
    started_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    resolved_at  TIMESTAMP,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE risks (
    id          BIGSERIAL PRIMARY KEY,
    title       VARCHAR(500) NOT NULL,
    description TEXT,
    probability VARCHAR(10)  NOT NULL DEFAULT 'MEDIUM',
    impact      VARCHAR(10)  NOT NULL DEFAULT 'MEDIUM',
    status      VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    mitigation  TEXT,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE people (
    id               BIGSERIAL PRIMARY KEY,
    full_name        VARCHAR(200) NOT NULL,
    login            VARCHAR(100),
    email            VARCHAR(200),
    phone            VARCHAR(50),
    domain           VARCHAR(200),
    current_task     TEXT,
    capacity_sprint  INT,
    capacity_month   INT,
    capacity_quarter INT,
    notes            TEXT,
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE people_notes (
    id          BIGSERIAL PRIMARY KEY,
    person_id   BIGINT       REFERENCES people(id) ON DELETE CASCADE,
    note        TEXT         NOT NULL,
    tags        VARCHAR(500),
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE email_state (
    id             BIGSERIAL PRIMARY KEY,
    message_id     VARCHAR(500) UNIQUE NOT NULL,
    subject        VARCHAR(1000),
    sender         VARCHAR(500),
    received_at    TIMESTAMP,
    classification VARCHAR(20),
    status         VARCHAR(20)  NOT NULL DEFAULT 'NEW',
    summary        TEXT,
    created_at     TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_tasks_plan_id       ON tasks(plan_id);
CREATE INDEX idx_tasks_status        ON tasks(status);
CREATE INDEX idx_tasks_source        ON tasks(source);
CREATE INDEX idx_daily_plans_date    ON daily_plans(plan_date);
CREATE INDEX idx_incidents_status    ON incidents(status);
CREATE INDEX idx_risks_status        ON risks(status);
CREATE INDEX idx_people_notes_person ON people_notes(person_id);
