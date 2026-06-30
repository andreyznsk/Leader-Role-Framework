CREATE TABLE memory.agent_workspace_runs (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    mode        VARCHAR(32)  NOT NULL,
    provider    VARCHAR(64)  NOT NULL,
    prompt      TEXT,
    status      VARCHAR(32)  NOT NULL,
    duration_ms BIGINT,
    error_message TEXT,
    created_at  TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX idx_agent_workspace_runs_created ON memory.agent_workspace_runs(created_at DESC);
