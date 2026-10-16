# Audio Gear Aggregator

A Windows desktop app (Java 25, JavaFX 27, dark theme) for cataloguing, comparing and analysing headphones,
IEMs, amps and DACs, tracking wishlist prices, and pulling community discussion into a local, searchable index.

## Install it

Download `AudioGearAggregator-<version>.msi` from the repository's **Releases** page and run it. It installs for your
user only (no admin rights) with Start menu and desktop shortcuts, and bundles its own Java runtime. Running a newer
`.msi` replaces the old version; your catalog in `%APPDATA%\AudioGearAggregator\` is kept. The installer isn't
code-signed, so SmartScreen may warn: choose *More info → Run anyway*. Each release also has a portable `.zip`.

## Run it from source

```bash
./gradlew run
```

Build a standalone app folder with its own Java runtime (no Java install needed to run it):

```bash
./gradlew packageApp
```

The result is `build/jpackage/Audio Gear Aggregator/Audio Gear Aggregator.exe`. Copy the whole folder wherever
you like. `./gradlew packageInstaller` builds the `.msi` into `build/installer/`, but needs the WiX toolset on PATH;
CI builds it for every release.

## First steps

1. **Data & Settings → AutoEq measurements**: press *Load reviewer list*, tick reviewers (oratory1990 and
   crinacle are pre-ticked), *List measurement files*, then *Import all listed* or pick a few. Each file becomes
   a frequency-response curve; gear is created automatically and matched by brand and model on re-import.
2. Press **Import Harman targets** to enable target overlays, deviation views and preference scores.
3. In the **Catalog**, edit items to add impedance, sensitivity and the other specs (AutoEq only provides FR).
   Add amps with their output impedance and rated power to use **Pairing**.
4. For community search, add a **Brave Search API key** in *Data & Settings*. Brave covers Head-Fi, ASR,
   SBAF, Reddit and YouTube. A direct Reddit connection is optional (Reddit approves new API apps individually).
5. **No API keys?** Load a research file instead: *Data & Settings → Import research file (JSON)*. A research file
   bundles gear with specs, the threads and videos that discuss each item, and AutoEq names for curves.
   `research/2026-10-most-discussed.json` holds the 20 most-discussed items of Oct 2025 – Oct 2026. From a
   terminal: `./gradlew importResearch -Pfile=research/<file>.json` (backs up the database first).
   A new `research/YYYY-MM-monthly.json` arrives as a pull request each month (Claude's scheduled research
   refresh, [.claude/skills/research-refresh](.claude/skills/research-refresh/SKILL.md)); merge it, then import it.
   Importing again is safe: for gear already in the catalog it only fills empty fields and adds tags, so specs and
   notes you edited are kept.

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

58 tests cover the analysis maths, parsers, SQLite repositories (real database files), importers, the research files and search
providers (local stub HTTP server, no real API calls) and the catalog/compare screen logic.

`./gradlew screenshots` seeds a throwaway data folder (real AutoEq downloads plus labelled sample data) and
renders every screen to `build/screenshots/`. It never touches your real data folder.

GitHub Actions ([.github/workflows/ci.yml](.github/workflows/ci.yml)) runs the tests on Windows for every push to
`main` and every pull request. To publish a release, bump `version` in `build.gradle.kts`, then push a matching tag
(`git tag v1.1.0 && git push origin v1.1.0`). CI builds the `.msi` and the portable zip and publishes both as a
GitHub release. Running the workflow by hand from the Actions tab builds the same files as an artifact, without a
release.

Design spec: [docs/superpowers/specs/2026-10-15-audio-gear-aggregator-design.md](docs/superpowers/specs/2026-10-15-audio-gear-aggregator-design.md)

Package layout: `domain` (records), `analysis` (pure maths), `db` (SQLite + JDBI, migrations), `importers`
(AutoEq, curve files, CSV), `search` (Brave, Reddit, caching, linking), `settings`, `net`, `ui` (JavaFX screens,
custom curve chart), `app` (startup and wiring).
