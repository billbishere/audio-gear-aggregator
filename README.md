# Audio Gear Aggregator

A Windows desktop app (Java 25, JavaFX 27, dark theme) for cataloguing, comparing and analysing headphones,
IEMs, amps and DACs, tracking wishlist prices, and pulling community discussion into a local, searchable index.

## Run it

```bash
./gradlew run
```

Build a standalone app folder with its own Java runtime (no Java install needed to run it):

```bash
./gradlew packageApp
```

The result is `build/jpackage/Audio Gear Aggregator/Audio Gear Aggregator.exe`. Copy the whole folder wherever
you like. (An `.msi` installer would also need the WiX toolset.)

## First steps

1. **Data & Settings → AutoEq measurements**: press *Load reviewer list*, tick reviewers (oratory1990 and
   crinacle are pre-ticked), *List measurement files*, then *Import all listed* or pick a few. Each file becomes
   a frequency-response curve; gear is created automatically and matched by brand and model on re-import.
2. Press **Import Harman targets** to enable target overlays, deviation views and preference scores.
3. In the **Catalog**, edit items to add impedance, sensitivity and the other specs (AutoEq only provides FR).
   Add amps with their output impedance and rated power to use **Pairing**.
4. For community search, add a **Brave Search API key** in *Data & Settings*. Brave covers Head-Fi, ASR,
   SBAF and Reddit. A direct Reddit connection is optional (Reddit approves new API apps individually).

## Screens

| Screen | What it does |
|---|---|
| Catalog | Filterable, sortable table of everything; detail pane with specs, curves, linked threads, price |
| Compare | Up to 6 items side by side; best values highlighted; FR overlay |
| Curves | FR / impedance overlay, alignment, smoothing, A−B difference, deviation from target, Harman preference score, PNG export |
| Pairing | Voltage/power needed for a target loudness, amp headroom, 1/8 rule, FR change caused by output impedance |
| Discover | Community search (cached, de-duplicated, auto-linked to gear) and offline full-text search of stored threads |
| Wishlist | Target prices, best current price per retailer, price history |
| Data & Settings | AutoEq import, curve files (CSV, REW, squig.link), catalog CSV import/export, backups, API keys |

## Where things live

- Data: `%APPDATA%\AudioGearAggregator\` — `catalog.db` (SQLite), `settings.properties` (API keys),
  `backups\` (taken automatically before schema upgrades), `logs\`, `cache\`.
- Run against a different folder: `./gradlew run -Daudiogear.home=C:\some\folder`.

AutoEq measurements belong to their original reviewers: fine for personal use, don't redistribute them.

## Development

```bash
./gradlew test
```

52 tests cover the analysis maths, parsers, SQLite repositories (real database files), importers and search
providers (local stub HTTP server, no real API calls) and the catalog/compare screen logic.

`./gradlew screenshots` seeds a throwaway data folder (real AutoEq downloads plus labelled sample data) and
renders every screen to `build/screenshots/`. It never touches your real data folder.

Design spec: [docs/superpowers/specs/2026-10-15-audio-gear-aggregator-design.md](docs/superpowers/specs/2026-10-15-audio-gear-aggregator-design.md)

Package layout: `domain` (records), `analysis` (pure maths), `db` (SQLite + JDBI, migrations), `importers`
(AutoEq, curve files, CSV), `search` (Brave, Reddit, caching, linking), `settings`, `net`, `ui` (JavaFX screens,
custom curve chart), `app` (startup and wiring).
