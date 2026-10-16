-- Daily updates: which update files (feeds, research) have been applied, and a fast lookup of curve origins
-- so AutoEq files already imported are not imported again.
CREATE TABLE applied_update (
    name       TEXT PRIMARY KEY,
    kind       TEXT NOT NULL,
    applied_at TEXT NOT NULL
);

CREATE INDEX curve_origin ON curve (origin);

-- AutoEq files the daily update has already handled. Unlike curve origins, an entry stays when the user deletes the
-- curve or its gear, so deletions stick, and two AutoEq files that land on the same curve don't swap places every day.
CREATE TABLE autoeq_seen (
    origin TEXT PRIMARY KEY
);
INSERT OR IGNORE INTO autoeq_seen (origin) SELECT origin FROM curve WHERE origin IS NOT NULL;
