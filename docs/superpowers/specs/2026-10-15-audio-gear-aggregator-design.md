# Audio Gear Aggregator — Design Spec

Date: 2026-10-15
Status: Approved (design sections approved in chat)

## 1. Intent

A personal, daily-use Windows desktop app for cataloguing, comparing and analysing
headphones, IEMs, amps and DACs, tracking wishlist prices, and pulling community
discussion about that gear into a local, searchable index.

Success means:

- The catalog can hold thousands of models (bulk-imported) and stays fast to filter.
- Any 2–6 items can be compared side by side, with curves overlaid.
- The app answers practical questions: "Can this amp drive this headphone?",
  "How will this amp's output impedance change this IEM's sound?",
  "How close is this headphone to the Harman target?", "Is it below my target price yet?"
- Community threads from Reddit, Head-Fi, Audio Science Review and Super Best Audio
  Friends can be found per model and are kept locally for offline full-text search.

### Decided with the user

- Purpose: personal daily tool (real data and workflow over showcase polish).
- Gear scope: headphones, IEMs, amps, DACs (and combo amp/DACs).
- Curves: impedance and frequency response, plus any useful derived analysis.
- Sources: Reddit, Head-Fi, ASR, SBAF.
- Stack: approach A — SQL-first.

### Assumptions (stated to the user, not objected to)

- Windows is the primary platform; single user; no accounts or sync.
- Wishlist prices are entered manually (no retailer scraping).
- Bulk measurement data comes from the AutoEq project's public dataset; manual entry
  and CSV import also exist.
- API keys live in a settings file in the user profile, never in the database.

### Out of scope

Cloud sync, retailer price scraping, accounts/multi-user, EQ preset generation,
measurement capture, mobile.

## 2. External constraints (researched 2026-10-15)

- **Reddit API**: since late 2025, new OAuth apps (including personal scripts) reportedly
  require Reddit's pre-approval, and approvals are slow or never come. Sources are
  third-party, so Reddit cannot be the backbone. Reddit content is reached through the
  web search provider (`site:reddit.com`); a direct Reddit provider is optional and
  disabled until the user supplies approved credentials.
- **Brave Search API**: the free plan is closed to new users; new accounts get roughly
  $5/month of credit (about 1,000 queries). Results are cached locally to stretch this.
  The provider sits behind an interface so another search API can replace it.
- **AutoEq dataset**: `measurements/<source>/data/<over-ear|in-ear|earbud>/<Model>.csv`,
  columns `frequency,raw`, about 1,000 log-spaced points from 20 Hz to 20 kHz. FR only, no
  impedance. Measurements belong to their original reviewers (oratory1990, crinacle,
  Rtings, …); fine for local personal use and must not be redistributed.
- **Forums** (Head-Fi, ASR, SBAF) have no public search API. The app does not scrape forum
  pages; it stores only what search APIs return (title, URL, snippet, date).

## 3. Stack

- Java 25 (installed: Microsoft JDK 25.0.4), Gradle 9.8 (installed), JavaFX 27 (OpenJFX via
  the `org.openjfx.javafxplugin` Gradle plugin).
- AtlantaFX dark theme as the control base, plus app CSS.
- SQLite via `org.xerial:sqlite-jdbc`; JDBI 3 for mapping; a small built-in migration
  runner (versioned `V<n>__name.sql` resources, `schema_version` table) instead of Flyway,
  to avoid one more dependency to keep compatible with SQLite and JDK 25.
- SQLite FTS5 for full-text search over threads.
- `java.net.http.HttpClient` + Jackson for API calls.
- Custom Canvas-based chart component (log-frequency axis; the built-in JavaFX
  `LineChart` has no log axis and slows with many overlaid series).
- JUnit 5 for tests; JDK `com.sun.net.httpserver` for stub HTTP servers in tests.
- `java.util.logging` to a rolling file.

Pinned versions (checked 2026-10-15): JavaFX 27 (AtlantaFX 3.0.0 is built against it;
JavaFX 27 targets Java 25), AtlantaFX 3.0.0, sqlite-jdbc 3.53.4.0, JDBI 3.55.0,
Jackson 2.22.3, JUnit 6.1.3, Gradle plugin `org.openjfx.javafxplugin` 0.1.0.

## 4. Architecture

Single Gradle module, base package `audiogear`. Each package has one job; dependencies
point inward toward `domain` and `analysis`.

| Package | Responsibility | Depends on |
|---|---|---|
| `domain` | Immutable records: `Gear`, `TransducerSpec`, `AmpSpec`, `AmpPower`, `DacSpec`, `Cable`, `Curve`, `WishlistItem`, `PriceEntry`, `Thread`, enums | — |
| `analysis` | Pure maths: curve resampling/normalising/smoothing/difference, sensitivity conversion, pairing, impedance shift, target deviation, preference score | `domain` |
| `db` | `Database` (connection, pragmas, migration runner, pre-migration backup), one JDBI repository per aggregate | `domain` |
| `importers` | AutoEq index + import, curve file parsers (CSV, REW `.txt`, squig.link `.txt`), catalog CSV import/export | `domain`, `db` |
| `search` | `SearchProvider` interface, `BraveSearchProvider`, `RedditProvider`, `SearchService`, URL normaliser, gear-name matcher, rate limiter | `domain`, `db` |
| `settings` | Load/save `settings.properties` (API keys, currency, defaults) | — |
| `ui` | App shell + navigation, one view + view-model per screen, `CurveChart`, dialogs, CSS | everything above |
| `app` | `Main`, manual dependency wiring, background executor | everything |

Threading: one shared `ExecutorService` for DB/network/import work, run through JavaFX
`Task`s; UI state is changed only on the FX thread. View-models expose JavaFX properties
and call services, so they can be tested without screens.

Files on disk (under `%APPDATA%\AudioGearAggregator\`):

- `catalog.db` — the SQLite database (WAL mode, `foreign_keys=ON`)
- `backups\catalog-<timestamp>.db` — copy taken before any migration runs
- `settings.properties` — API keys and preferences
- `logs\app.log`

## 5. Data model

All tables have an integer primary key `id` unless noted. Timestamps are ISO-8601 UTC text.

**gear** — `type` (HEADPHONE, IEM, AMP, DAC, AMP_DAC), `brand`, `model`, `variant`,
`release_year`, `msrp`, `msrp_currency`, `status` (NONE, WATCHING, WISHLIST, OWNED, SOLD),
`notes`, `created_at`, `updated_at`. Unique on (`brand`, `model`, `variant`).

**transducer_spec** (PK `gear_id`) — for HEADPHONE and IEM: `form` (OVER_EAR, ON_EAR,
IN_EAR, EARBUD), `back` (OPEN, SEMI_OPEN, CLOSED), `driver_type` (DYNAMIC, PLANAR,
ELECTROSTATIC, BA, HYBRID, TRIBRID, OTHER), `driver_config` (text, e.g. "1DD+4BA"),
`driver_size_mm`, `impedance_ohm`, `sensitivity`, `sensitivity_unit` (DB_PER_MW,
DB_PER_V), `weight_g`, `connector` (text, e.g. "2-pin 0.78").

**amp_spec** (PK `gear_id`) — for AMP and AMP_DAC: `output_impedance_ohm`,
`max_voltage_vrms`, `max_current_ma`, `gain_settings` (text), `topology`, `outputs` (text).

**amp_power** — `gear_id`, `output` (SE, BALANCED), `load_ohm`, `power_mw`. Several rows
per amp (e.g. 32 Ω and 300 Ω figures).

**dac_spec** (PK `gear_id`) — for DAC and AMP_DAC: `chip`, `sinad_db`,
`output_voltage_vrms`, `max_sample_rate_khz`, `dsd` (bool), `inputs`, `outputs`.

**cable** — `gear_id` (nullable; set when it is a gear item's stock cable), `name`,
`source_plug` (3.5, 4.4, 6.35, XLR4, OTHER), `device_plug` (text), `length_m`,
`conductor`, `loop_resistance_ohm`, `owned` (bool).

**curve** — `gear_id` (nullable; NULL for target curves), `kind` (FR, IMPEDANCE, TARGET),
`source` (e.g. "oratory1990", "user"), `label`, `channel` (L, R, AVG), `point_count`,
`freq_hz` BLOB, `value` BLOB, `phase_deg` BLOB (nullable, impedance only),
`origin` (file path or URL), `created_at`. BLOBs are little-endian float32 arrays of
length `point_count`. `value` is dB for FR/TARGET and ohms for IMPEDANCE.

**wishlist_item** (PK `gear_id`) — `priority` (1–5), `target_price`, `currency`, `notes`.
Created when gear status first becomes WISHLIST; kept if status later changes so details
are not lost. The Wishlist screen lists gear whose status is WISHLIST.

**price_entry** — `gear_id`, `retailer`, `price`, `currency`, `condition` (NEW, USED,
B_STOCK), `url`, `seen_at`.

**tag**, **gear_tag** — user labels (`tag.name` unique; `gear_tag` is the join).

**thread** — `source` (REDDIT, HEAD_FI, ASR, SBAF, OTHER), `url` (normalised, unique),
`title`, `snippet`, `author`, `posted_at`, `score`, `comment_count` (nullable; Reddit
only), `first_seen_at`, `last_seen_at`.

**thread_gear** — (`thread_id`, `gear_id`) primary key, `link_kind` (AUTO, MANUAL,
REJECTED). REJECTED stops the auto-linker re-adding a link the user removed.

**thread_fts** — FTS5 external-content table over `thread(title, snippet)`, kept in sync
by triggers.

**search_cache** — `provider`, `query_text`, `fetched_at`, `result_count`; unique on
(`provider`, `query_text`).

**search_cache_thread** — (`cache_id`, `thread_id`) primary key, `rank`. Remembers which
threads a cached query returned, so a cached search shows the same results without an
API call.

## 6. Screens

Left navigation rail; one view at a time; selection in Catalog can be sent to Compare,
Curves or Pairing.

1. **Catalog** — `TableView` over all gear with type tabs (All / Headphones / IEMs /
   Amps / DACs). Filter bar: text, status, driver type, form, impedance range,
   sensitivity range, price range, tags. Column chooser. Columns include thread mentions
   and recent mentions (last 90 days). Detail pane: spec editor, curves, linked threads,
   price history. Multi-select actions: Compare, Overlay curves, Add to wishlist.
   Filtering is in memory over summary rows (sized for 10,000+ rows). AMP_DAC items
   appear under both the Amps and DACs tabs. The price filter uses the best current
   price when one exists, otherwise MSRP.
2. **Compare** — 2–6 columns; one row per spec field; per-row "best" highlight where a
   direction makes sense (lower output impedance, higher SINAD, lower weight, etc.);
   "Differences only" toggle; FR overlay below.
3. **Curves** — `CurveChart` workspace: add/remove curves, normalise at 1 kHz / 500 Hz /
   none, smoothing (none, 1/3, 1/6, 1/12 octave), A−B difference, deviation from a
   chosen target, impedance magnitude with optional phase axis, PNG export.
4. **Pairing** — pick a headphone/IEM, an amp, optional cable, target peak SPL (default
   110 dB). Shows required voltage and power, available power at that load, headroom dB,
   verdict, source-impedance ratio vs the 1/8 rule, and (if an impedance curve exists)
   the FR-shift chart with its maximum deviation.
5. **Discover** — query box with gear picker; source toggles (Reddit, Head-Fi, ASR,
   SBAF); results list with source badge, date, snippet, score/comments where known;
   open in system browser; link/unlink to gear; "Search saved threads" runs FTS5 locally
   with no API call. Banner per failing source.
6. **Wishlist** — priority, target price, best current price (lowest latest entry per
   retailer), gap to target, below-target flag, add-price dialog, price-history sparkline.
7. **Data & Settings** — AutoEq import (choose sources and forms; import all or
   per-model), curve file import, catalog CSV import/export, database backup, API keys,
   currency, search cache TTL (default 7 days), default target SPL.

Theme: AtlantaFX dark base; app CSS sets the accent colour and an 8-colour curve palette
that stays distinguishable for common colour-vision deficiencies.

## 7. Search and indexing

```java
interface SearchProvider {
    String id();
    boolean isConfigured();
    List<SearchHit> search(SearchQuery query) throws SearchException;
}
```

- **BraveSearchProvider** — `GET https://api.search.brave.com/res/v1/web/search`,
  header `X-Subscription-Token`, `count=20`. The query is the gear name in quotes plus
  site filters for the enabled sources (head-fi.org, audiosciencereview.com,
  superbestaudiofriends.org, reddit.com). If Brave honours `OR` between `site:` filters
  it is one request (verified: Brave supports uppercase `AND`/`OR`/`NOT`); a setting
  switches to one request per site if combined queries prove unreliable.
  Rate-limited to Brave's per-second cap.
- **RedditProvider** (optional) — app-only OAuth; searches
  `r/headphones+HeadphoneAdvice+audiophile+inear` with `restrict_sr`. Disabled until
  credentials are set.
- **SearchService**
  1. For each enabled provider, skip it if `search_cache` holds the same query newer than
     the TTL (show stored results instead).
  2. Call providers in parallel with a 10 s timeout each.
  3. Normalise URLs: strip tracking parameters and fragments; XenForo thread URLs drop
     `/page-N` and `#post-…`; Reddit uses the canonical permalink.
  4. Classify `source` from the host.
  5. Upsert into `thread` (update `last_seen_at`, snippet, score).
  6. Auto-link to gear: every hit of a gear-specific query links to that gear. Hits are
     also matched against the whole catalog: normalise names (lowercase, drop
     spaces/punctuation, so "HD 650" = "HD-650" = "hd650"); a model alone matches when
     its normalised form has at least 4 characters and contains a digit; otherwise the
     brand must match too. Never re-add a REJECTED link.
  7. Record the query in `search_cache` and its results in `search_cache_thread`.
- Metrics shown in Catalog: count of linked threads, and count with `posted_at` (or
  `first_seen_at` if unknown) in the last 90 days.

## 8. Importers

- **AutoEq**: list available files per source with the GitHub git-trees API (one request
  per source; under the 60/hour unauthenticated limit), download with
  `raw.githubusercontent.com`. Folder maps to type/form: over-ear → HEADPHONE/OVER_EAR,
  in-ear → IEM/IN_EAR, earbud → IEM/EARBUD. Brand is parsed from the file name using a
  known multi-word brand list (e.g. "Campfire Audio", "64 Audio", "Audio-Technica"),
  falling back to the first word; the user can edit it later. Existing gear is matched by
  normalised brand + model, so re-imports add curves instead of duplicating gear. Target
  curves come from AutoEq's targets folder (Harman over-ear 2018 and in-ear 2019).
- **Curve files**: CSV/TSV with a header or without one; REW text exports (lines
  starting with `*` are comments; columns freq, value, optional phase); squig.link `.txt`.
  Validation: numeric, frequencies strictly increasing, at least 2 points, frequencies
  within 1 Hz–100 kHz.
- **Catalog CSV**: one row per gear item, columns named after the spec fields; export
  writes the same format.

Bulk imports never stop on a bad file; they finish with a summary
("N imported, M skipped") and a log of skipped files and reasons.

## 9. Analysis

All functions are pure and unit-tested with hand-computed values.

- **Grid**: curves are resampled onto a shared log grid, 1/48-octave steps from 20 Hz to
  20 kHz, by linear interpolation in log-frequency. Points outside a curve's range are
  left empty, not extrapolated.
- **Normalise**: subtract the value at the reference frequency (1 kHz or 500 Hz).
- **Smoothing**: moving average over a window of 1/N octave in log-frequency.
- **Difference**: A − B on the shared grid.
- **Sensitivity**: S_dB/V = S_dB/mW + 10·log₁₀(1000 / Z).
- **Drive needed**: V = 10^((L_target − S_dB/V) / 20) Vrms; P = V² / Z.
- **Available power at load Z**, in order of preference:
  1. Both limits known: the delivered voltage is V_max · Z / (Z + Z_s) (the amp's own
     output impedance divides it), P = min(V_load² / Z, I_max² · Z).
  2. Rated `amp_power` points for the chosen output: interpolate in log-log space between
     neighbouring loads. Above the highest rated load assume constant voltage
     (P ∝ 1/Z); below the lowest assume constant current (P ∝ Z).
  3. Only one limit known: use that limit's formula. Headroom dB =
  10·log₁₀(P_available / P_needed). Verdict: ≥ 6 dB comfortable, 0–6 dB marginal,
  < 0 dB not enough.
- **Source impedance**: Z_s = amp output impedance + cable loop resistance; report
  Z_load / Z_s and whether Z_s ≤ Z_load / 8.
- **FR shift**: with complex Z(f) = |Z|·e^(jφ) (φ = 0 when phase is absent),
  shift(f) = 20·log₁₀|Z(f) / (Z(f) + Z_s)|, normalised at 1 kHz. Report the curve and its
  maximum absolute deviation.
- **Target deviation**: FR − target on the shared grid after both are normalised.
- **Predicted preference score** (formulas from AutoEq's `frequency_response.py`):
  error = FR − target, both interpolated (linear in log-frequency) onto the 121-point
  Harman preference frequency list (20 Hz – 20 kHz, ~1/12 octave).
  - Over-ear: over 50 Hz – 10 kHz, SD = sample standard deviation of the error,
    slope = least-squares slope of error vs ln(f);
    score = 114.490443008238 − 12.62·SD − 15.5163857197367·|slope|.
  - In-ear: SD and slope over 20 Hz – 10 kHz; AE = mean |error − error(500 Hz)| over
    40 Hz – 10 kHz; score = 100.0795 − 8.5·SD − 6.796·|slope| − 3.475·AE.

## 10. Error handling

- **Search**: each provider fails on its own; a banner names the source and reason
  (timeout, 401 bad key, 429 rate limit). A provider without a key is shown disabled with
  a link to Settings.
- **Network**: 10 s timeouts; no automatic retry loops; a 429 sets a cool-down shown in
  the banner.
- **Imports**: per-file validation and a final summary, as in section 8.
- **Database**: back up `catalog.db` before running pending migrations; if a migration
  fails, show a dialog naming the backup file and exit without touching data further.
- **Editors**: numeric and range validation on input; invalid fields are highlighted and
  cannot be saved.
- Unexpected exceptions on background tasks are logged and shown as a non-blocking
  error notice.

## 11. Testing

- Unit tests (JUnit 5): every `analysis` function, curve parsers (fixture files per
  format, including malformed ones), URL normaliser, gear-name matcher, Brave query
  builder, brand parser.
- Repository tests against a temporary SQLite file with all migrations applied (no
  mocks for the database).
- Provider tests against a local stub HTTP server replaying recorded JSON responses —
  no real API calls or credit spent in tests.
- View-model tests for filter logic, compare "best value" logic and wishlist price
  calculations, without starting the JavaFX toolkit.
- Screens are checked by running the app.

## 12. Packaging

- Development: `gradle run`.
- Distribution: jpackage (through a Gradle jlink/jpackage plugin) producing a Windows
  installer. If the WiX toolset is not installed, produce an app-image folder with a
  launcher `.exe` instead.

## 13. Verification notes

1. Versions: pinned in section 3. Flyway replaced by a built-in runner.
2. Brave: uppercase `OR` is supported between operators.
3. AutoEq: formulas in section 9; targets are `targets/Harman over-ear 2018.csv` and
   `targets/Harman in-ear 2019.csv`, same `frequency,raw` format as measurements.
4. GitHub trees: list one source at a time (`measurements` tree, then each source's tree
   with `recursive=1`) so responses stay well under the truncation limit.
5. WiX is not installed, so packaging produces a jpackage app-image (folder with
   launcher `.exe`).

The user asked to skip further approval steps and the separate plan document; the build
follows this spec directly.
