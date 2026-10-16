-- Adds YOUTUBE as a thread source. SQLite cannot alter a CHECK constraint, so the thread table is rebuilt
-- (ids are kept, so links, cache entries and the full-text index stay valid). The migrator turns foreign keys
-- off while this runs, so dropping the old table does not cascade.

CREATE TABLE thread_v2 (
    id            INTEGER PRIMARY KEY,
    source        TEXT NOT NULL CHECK (source IN ('REDDIT', 'HEAD_FI', 'ASR', 'SBAF', 'YOUTUBE', 'OTHER')),
    url           TEXT NOT NULL UNIQUE,
    title         TEXT NOT NULL,
    snippet       TEXT,
    author        TEXT,
    posted_at     TEXT,
    score         INTEGER,
    comment_count INTEGER,
    first_seen_at TEXT NOT NULL,
    last_seen_at  TEXT NOT NULL
);

INSERT INTO thread_v2 (id, source, url, title, snippet, author, posted_at, score, comment_count, first_seen_at, last_seen_at)
SELECT id, source, url, title, snippet, author, posted_at, score, comment_count, first_seen_at, last_seen_at FROM thread;

UPDATE thread_v2 SET source = 'YOUTUBE' WHERE source = 'OTHER' AND (url LIKE 'https://www.youtube.com/%' OR url LIKE 'https://youtu.be/%');

DROP TABLE thread;

ALTER TABLE thread_v2 RENAME TO thread;

CREATE TRIGGER thread_ai AFTER INSERT ON thread BEGIN
    INSERT INTO thread_fts (rowid, title, snippet) VALUES (new.id, new.title, new.snippet);
END;

CREATE TRIGGER thread_ad AFTER DELETE ON thread BEGIN
    INSERT INTO thread_fts (thread_fts, rowid, title, snippet) VALUES ('delete', old.id, old.title, old.snippet);
END;

CREATE TRIGGER thread_au AFTER UPDATE OF title, snippet ON thread BEGIN
    INSERT INTO thread_fts (thread_fts, rowid, title, snippet) VALUES ('delete', old.id, old.title, old.snippet);
    INSERT INTO thread_fts (rowid, title, snippet) VALUES (new.id, new.title, new.snippet);
END;
