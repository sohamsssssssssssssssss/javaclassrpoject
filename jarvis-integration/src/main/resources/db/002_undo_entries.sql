CREATE TABLE IF NOT EXISTS undo_entries (
    entry_id TEXT PRIMARY KEY NOT NULL,
    request_id TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('MOVE','RENAME')),
    source_path TEXT NOT NULL,
    target_path TEXT NOT NULL,
    recorded_at TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('ACTIVE','UNDONE','FAILED')),
    error_code TEXT,
    error_message TEXT
);
CREATE INDEX IF NOT EXISTS idx_undo_entries_recorded
    ON undo_entries(recorded_at DESC);
PRAGMA user_version = 2;
