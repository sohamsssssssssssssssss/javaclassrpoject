CREATE TABLE IF NOT EXISTS command_history (
    request_id TEXT PRIMARY KEY NOT NULL,
    original_text TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('SUCCEEDED','FAILED','CANCELLED','REJECTED')),
    summary TEXT NOT NULL,
    error_code TEXT,
    error_message TEXT,
    submitted_at TEXT NOT NULL,
    started_at TEXT NOT NULL,
    completed_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_command_history_completed
    ON command_history(completed_at DESC);
PRAGMA user_version = 1;
