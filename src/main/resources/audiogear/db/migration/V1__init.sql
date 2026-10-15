-- Audio Gear Aggregator schema, version 1.
-- Statements end with ';' at end of line; trigger bodies end with a line containing only 'END;'.

CREATE TABLE gear (
    id            INTEGER PRIMARY KEY,
    type          TEXT NOT NULL CHECK (type IN ('HEADPHONE', 'IEM', 'AMP', 'DAC', 'AMP_DAC')),
    brand         TEXT NOT NULL COLLATE NOCASE,
    model         TEXT NOT NULL COLLATE NOCASE,
    variant       TEXT NOT NULL DEFAULT '' COLLATE NOCASE,
    release_year  INTEGER,
    msrp          REAL CHECK (msrp IS NULL OR msrp >= 0),
    msrp_currency TEXT,
    status        TEXT NOT NULL DEFAULT 'NONE' CHECK (status IN ('NONE', 'WATCHING', 'WISHLIST', 'OWNED', 'SOLD')),
    notes         TEXT,
    created_at    TEXT NOT NULL,
    updated_at    TEXT NOT NULL,
    UNIQUE (brand, model, variant)
);

CREATE TABLE transducer_spec (
    gear_id          INTEGER PRIMARY KEY REFERENCES gear (id) ON DELETE CASCADE,
    form             TEXT CHECK (form IN ('OVER_EAR', 'ON_EAR', 'IN_EAR', 'EARBUD')),
    back             TEXT CHECK (back IN ('OPEN', 'SEMI_OPEN', 'CLOSED')),
    driver_type      TEXT CHECK (driver_type IN ('DYNAMIC', 'PLANAR', 'ELECTROSTATIC', 'BA', 'HYBRID', 'TRIBRID', 'OTHER')),
    driver_config    TEXT,
    driver_size_mm   REAL,
    impedance_ohm    REAL CHECK (impedance_ohm IS NULL OR impedance_ohm > 0),
    sensitivity      REAL,
    sensitivity_unit TEXT CHECK (sensitivity_unit IN ('DB_PER_MW', 'DB_PER_V')),
    weight_g         REAL,
    connector        TEXT
);

CREATE TABLE amp_spec (
    gear_id              INTEGER PRIMARY KEY REFERENCES gear (id) ON DELETE CASCADE,
    output_impedance_ohm REAL CHECK (output_impedance_ohm IS NULL OR output_impedance_ohm >= 0),
    max_voltage_vrms     REAL CHECK (max_voltage_vrms IS NULL OR max_voltage_vrms > 0),
    max_current_ma       REAL CHECK (max_current_ma IS NULL OR max_current_ma > 0),
    gain_settings        TEXT,
    topology             TEXT,
    outputs              TEXT
);

CREATE TABLE amp_power (
    id       INTEGER PRIMARY KEY,
    gear_id  INTEGER NOT NULL REFERENCES gear (id) ON DELETE CASCADE,
    output   TEXT NOT NULL CHECK (output IN ('SE', 'BALANCED')),
    load_ohm REAL NOT NULL CHECK (load_ohm > 0),
    power_mw REAL NOT NULL CHECK (power_mw > 0)
);
CREATE INDEX amp_power_gear ON amp_power (gear_id);

CREATE TABLE dac_spec (
    gear_id             INTEGER PRIMARY KEY REFERENCES gear (id) ON DELETE CASCADE,
    chip                TEXT,
    sinad_db            REAL,
    output_voltage_vrms REAL,
    max_sample_rate_khz REAL,
    dsd                 INTEGER,
    inputs              TEXT,
    outputs             TEXT
);

CREATE TABLE cable (
    id                  INTEGER PRIMARY KEY,
    gear_id             INTEGER REFERENCES gear (id) ON DELETE SET NULL,
    name                TEXT NOT NULL,
    source_plug         TEXT,
    device_plug         TEXT,
    length_m            REAL,
    conductor           TEXT,
    loop_resistance_ohm REAL CHECK (loop_resistance_ohm IS NULL OR loop_resistance_ohm >= 0),
    owned               INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE curve (
    id          INTEGER PRIMARY KEY,
    gear_id     INTEGER REFERENCES gear (id) ON DELETE CASCADE,
    kind        TEXT NOT NULL CHECK (kind IN ('FR', 'IMPEDANCE', 'TARGET')),
    source      TEXT NOT NULL,
    label       TEXT NOT NULL,
    channel     TEXT NOT NULL DEFAULT 'AVG' CHECK (channel IN ('L', 'R', 'AVG')),
    point_count INTEGER NOT NULL,
    freq_hz     BLOB NOT NULL,
    value       BLOB NOT NULL,
    phase_deg   BLOB,
    origin      TEXT,
    created_at  TEXT NOT NULL
);
CREATE INDEX curve_gear ON curve (gear_id, kind);

CREATE TABLE wishlist_item (
    gear_id      INTEGER PRIMARY KEY REFERENCES gear (id) ON DELETE CASCADE,
    priority     INTEGER NOT NULL DEFAULT 3 CHECK (priority BETWEEN 1 AND 5),
    target_price REAL CHECK (target_price IS NULL OR target_price >= 0),
    currency     TEXT,
    notes        TEXT
);

CREATE TABLE price_entry (
    id        INTEGER PRIMARY KEY,
    gear_id   INTEGER NOT NULL REFERENCES gear (id) ON DELETE CASCADE,
    retailer  TEXT NOT NULL,
    price     REAL NOT NULL CHECK (price >= 0),
    currency  TEXT NOT NULL,
    condition TEXT NOT NULL DEFAULT 'NEW' CHECK (condition IN ('NEW', 'USED', 'B_STOCK')),
    url       TEXT,
    seen_at   TEXT NOT NULL
);
CREATE INDEX price_entry_gear ON price_entry (gear_id, retailer, seen_at);

CREATE TABLE tag (
    id   INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE COLLATE NOCASE
);

CREATE TABLE gear_tag (
    gear_id INTEGER NOT NULL REFERENCES gear (id) ON DELETE CASCADE,
    tag_id  INTEGER NOT NULL REFERENCES tag (id) ON DELETE CASCADE,
    PRIMARY KEY (gear_id, tag_id)
);

CREATE TABLE thread (
    id            INTEGER PRIMARY KEY,
    source        TEXT NOT NULL CHECK (source IN ('REDDIT', 'HEAD_FI', 'ASR', 'SBAF', 'OTHER')),
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

CREATE TABLE thread_gear (
    thread_id INTEGER NOT NULL REFERENCES thread (id) ON DELETE CASCADE,
    gear_id   INTEGER NOT NULL REFERENCES gear (id) ON DELETE CASCADE,
    link_kind TEXT NOT NULL CHECK (link_kind IN ('AUTO', 'MANUAL', 'REJECTED')),
    PRIMARY KEY (thread_id, gear_id)
);
CREATE INDEX thread_gear_gear ON thread_gear (gear_id);

CREATE VIRTUAL TABLE thread_fts USING fts5(
    title, snippet,
    content = 'thread', content_rowid = 'id',
    tokenize = 'unicode61 remove_diacritics 2'
);

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

CREATE TABLE search_cache (
    id           INTEGER PRIMARY KEY,
    provider     TEXT NOT NULL,
    query_text   TEXT NOT NULL,
    fetched_at   TEXT NOT NULL,
    result_count INTEGER NOT NULL,
    UNIQUE (provider, query_text)
);

CREATE TABLE search_cache_thread (
    cache_id  INTEGER NOT NULL REFERENCES search_cache (id) ON DELETE CASCADE,
    thread_id INTEGER NOT NULL REFERENCES thread (id) ON DELETE CASCADE,
    rank      INTEGER NOT NULL,
    PRIMARY KEY (cache_id, thread_id)
);
