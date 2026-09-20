CREATE TABLE procedures (
 id TEXT NOT NULL, version INTEGER NOT NULL CHECK (version > 0),
 created_at BIGINT NOT NULL, document JSONB NOT NULL, PRIMARY KEY (id, version)
);
CREATE TABLE mission_settings (id TEXT PRIMARY KEY, document JSONB NOT NULL);
CREATE INDEX scheduled_runs ON runs (created_at) WHERE status = 'SCHEDULED';
-- Existing runs/commands deserialize with their v1 definition and timeout defaults.
-- SCHEDULED runs do not hold the instrument; one_active_run still guards activation.
