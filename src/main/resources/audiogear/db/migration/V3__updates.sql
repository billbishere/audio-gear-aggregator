-- Daily updates: which update files (feeds, research) have been applied, and a fast lookup of curve origins
-- so AutoEq files already imported are not imported again.
CREATE TABLE applied_update (
    name       TEXT PRIMARY KEY,
    kind       TEXT NOT NULL,
    applied_at TEXT NOT NULL
);

CREATE INDEX curve_origin ON curve (origin);
