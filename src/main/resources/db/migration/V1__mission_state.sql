CREATE TABLE runs (
 id UUID PRIMARY KEY, status TEXT NOT NULL, created_at BIGINT NOT NULL, document JSONB NOT NULL
);
-- The instrument is reserved even while a procedure is paused or its outcome is unknown.
CREATE UNIQUE INDEX one_active_run ON runs ((TRUE)) WHERE status IN ('RUNNING','PAUSED','ABORTING');
CREATE TABLE commands (
 id UUID PRIMARY KEY, run_id UUID NOT NULL REFERENCES runs(id), status TEXT NOT NULL,
 created_at BIGINT NOT NULL, document JSONB NOT NULL
);
CREATE INDEX commands_run ON commands(run_id, created_at);
CREATE TABLE events (
 sequence BIGSERIAL PRIMARY KEY, at BIGINT NOT NULL, level TEXT NOT NULL,
 message TEXT NOT NULL, run_id UUID, command_id UUID
);
CREATE INDEX events_run ON events(run_id, sequence);
CREATE TABLE telemetry (
 sequence BIGSERIAL PRIMARY KEY, received_at BIGINT NOT NULL, document JSONB NOT NULL
);
