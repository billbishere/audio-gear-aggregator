# Full-data installer and daily updates: design

Date: 2026-10-16. Status: approved in chat; this spec is for review before the implementation plan.

## Goal

Anyone who downloads the installer from GitHub gets the full catalog on first launch. From then on the app keeps
itself current every day, adding new measurements, new discussion threads and new research, and it says when a new
version is out. This works without the owner's PC and without anyone needing API keys.

## Decisions

- **Audience:** anyone who downloads the release from GitHub.
- **Ship a full snapshot in the installer** (owner's choice). The alternative, downloading AutoEq on first launch,
  was offered to avoid redistributing reviewer measurements and was declined. Mitigations: reviewer credit on every
  curve and in the README, plus an exclusion list that can drop any reviewer's data (section 7).
- **Daily updates use the methods that built the current data.** AutoEq listing and import gives measurements. A
  scrape of forums, Reddit and YouTube, in the existing research-file format, gives threads.
- **The daily scraping runs in GitHub Actions** (free on a public repo, built-in `GITHUB_TOKEN` only). The monthly
  Claude + Bright Data research refresh carries on unchanged as the curated layer.
- **Updates only ever add.** They add data or fill empty fields. They never overwrite or delete, and the app takes a
  backup before applying them.

## Architecture

| Unit | Runs | Does | Depends on |
|---|---|---|---|
| `SnapshotBuilder` (`audiogear.snapshot`) + Gradle `buildSnapshot` | CI, per release | Builds a fresh `seed/catalog.db` | `AutoEqClient`, `AutoEqImporter`, `ResearchImporter`, `UpdateFeed` |
| `FeedCollector` (`audiogear.feed`) + Gradle `collectFeed` | CI, daily | Turns RSS items into a dated research file | `RssReader`, `GearNameMatcher`, `AutoEqClient` (for gear names) |
| `daily-feed.yml` workflow | GitHub Actions, daily | Runs `collectFeed`, publishes to the `data` branch | `FeedCollector` |
| `UpdateFeed` (`audiogear.updates`) | App and CI | Reads `manifest.json` and the files it lists | `Http` |
| `Updater` (`audiogear.updates`) | App, daily | Applies new feed/research files, new AutoEq measurements, and the version notice | `UpdateFeed`, importers, `Database` |
| `SeedInstaller` (`audiogear.updates`) | App, at startup | Copies the bundled snapshot when there is no catalog yet | `AppPaths` |

All imports go through the importers that already exist. `ResearchImporter` already fills empty fields only and
merges tags.

## 1. Data published on the `data` branch

The daily workflow publishes to the `data` branch of `billbishere/audio-gear-aggregator`, so `main` isn't committed
to every day. The app reads it from `https://raw.githubusercontent.com/billbishere/audio-gear-aggregator/data/`.
The base URL is configurable so tests can point it at the stub server.

`manifest.json`:

```json
{ "generated": "2026-10-17T06:12:00Z",
  "latestApp": { "version": "1.2.0", "url": "https://github.com/billbishere/audio-gear-aggregator/releases/tag/v1.2.0" },
  "excludeSources": [],
  "files": [
    { "name": "research/2026-10-most-discussed.json", "kind": "research", "sha256": "<hex digest of the file>" },
    { "name": "feeds/2026-10-17.json", "kind": "feed", "sha256": "<hex digest of the file>" } ] }
```

- **`files`:** every research file on `main` (copied to `data` by the workflow) plus every daily feed file, oldest
  first. A file's `name` is its identity: once applied, it is never applied again.
- **Feed files:** use the existing research-file format with `window.from/to`. Each item is a matched gear
  (`type`, `brand`, `model`) with threads (`url`, `title`, `posted`, `source`). There's no `snippet`, so no post
  text is republished.

## 2. Snapshot in the installer

- **What `buildSnapshot` does**, starting from an empty database in `build/snapshot/seed/catalog.db`:
  1. Imports every AutoEq source except `excludeSources`, in all forms, plus the Harman targets.
  2. Applies every file in the current `manifest.json`, the same way the app does.
  3. Records each applied file in `applied_update`.

  In CI it authenticates AutoEq API calls with `GITHUB_TOKEN`.
- **It never copies anyone's catalog**, so no personal status, wishlist or price data is shipped.
- **Packaging:** `packageInstaller` and `packageApp` pass `--app-content build/snapshot/seed`, so the installed app
  contains `seed/catalog.db`. The release job runs `buildSnapshot` first.
- **First launch:** `SeedInstaller` runs before `Database.open`. It looks for `seed/catalog.db` in the jpackage app
  directory, which is the folder holding the main jar; the implementation confirms where `--app-content` places
  it. If the data folder has no `catalog.db` and the seed exists, it copies the seed in through a temporary file
  and an atomic rename, then the first daily update runs at once. An existing catalog is never touched.

## 3. Daily collector (cloud)

- **Schedule and tool:** `.github/workflows/daily-feed.yml` runs daily at 06:00 UTC, plus `workflow_dispatch`. It
  runs `gradlew collectFeed`.
- **Sources:** `feeds/sources.json` lists RSS URLs with a `source` tag, which is `ASR`, `HEAD_FI`, `SBAF`, `REDDIT`
  or `YOUTUBE`:
  - ASR, Head-Fi and SBAF: the headphone, IEM and amp/DAC forum feeds.
  - Reddit: r/headphones, r/HeadphoneAdvice and r/iems.
  - YouTube: the channel feeds of a list of reviewer channels.
- **Matching:** gear names come from the AutoEq index (parsed with `BrandParser`) plus the gear in all research
  files, which is where amps and DACs come from. `GearNameMatcher` matches item titles against those names, and only
  unambiguous matches are kept.
- **Output:** `feeds/YYYY-MM-DD.json` with `window.from` set to the previous run's `window.to`. Threads already in
  the last 14 days of feed files are dropped.
- **Publishing:** the workflow commits the new feed file, the copied `research/*.json` and the regenerated
  `manifest.json` to `data`. `latestApp` comes from the releases API, `excludeSources` from
  `config/snapshot-exclude-sources.txt` on `main`, and `sha256` from the file contents. If no thread matched, it
  still updates `manifest.generated`.
- **Failures:** a source that fails (Reddit may block datacenter IPs) is logged and skipped. The run fails only if
  every source fails.

## 4. Daily updater (in the app)

- **When it runs:** at startup on the worker thread, if `updates.lastRun` is more than 20 hours old, and every 24
  hours while the app is open. It needs `updates.daily=true`, the default.
- **Settings (Data & Settings):** a **Check for updates daily** box and an **Update now** button.
- **Steps:**
  1. Fetch `manifest.json`. On a network error, stop quietly and try again next time.
  2. Work out what is pending:
     - files whose `name` isn't in `applied_update`
     - AutoEq entries whose raw URL isn't already stored as a curve's `origin`, from sources not in `excludeSources`
  3. If anything is pending, take a backup named `catalog-update-YYYYMMDD-HHMMSS.db` and keep only the newest three
     of that kind. Schema-upgrade backups are never pruned.
  4. Download each pending file and check its `sha256`. Import it with `ResearchImporter`, without AutoEq lookups,
     and record it in `applied_update`. A file that fails is skipped and retried next run.
  5. Import the new AutoEq entries with `AutoEqImporter`.
  6. If `latestApp.version` is newer than the running version, show a status-bar notice with a **Download** button
     that opens the release page. The notice repeats until that version is installed or dismissed
     (`updates.dismissedVersion`).
  7. Show a summary in the status bar, e.g. "Updated: +12 measurements, +40 threads", and refresh the catalog view.
- **Concurrency:** only one update runs at a time, and it can be cancelled from the status bar's progress.

## 5. Schema and code changes

- **Migration `V3`:**
  - table `applied_update(name TEXT PRIMARY KEY, kind TEXT NOT NULL, applied_at TEXT NOT NULL)`
  - index `curve(origin)`
- **AutoEq dedupe fix:** `CurveRepository.origins()` returns the stored origins. `AutoEqImporter` skips entries
  whose `client.rawUrl(path)` is among them. This also fixes duplicate curves from **Import all listed** today.
- **`ResearchImporter` link kind:** a new parameter sets how threads are linked to their gear. Research files keep
  `MANUAL`. Feed files use `AUTO`, so machine-matched links look the same as the app's existing auto cross-links.
- **`Settings`:** `updates.daily`, `updates.lastRun`, `updates.dismissedVersion`, `updates.baseUrl` (testing only).
- **Running version:** read from the `jpackage.app-version` system property, falling back to the jar's
  `Implementation-Version`.

## 6. Error handling and safety

- Updates add data or fill empty fields only. Nothing is overwritten or deleted.
- A backup precedes every update that changes anything.
- Every network step treats offline, rate limits and HTTP errors as "skip and retry next run", and logs them.
- A file is recorded as applied only after its import commits.
- A corrupt download, which fails the `sha256` check, is never imported.
- A seed copy that fails leaves no `catalog.db` behind. The app then starts with an empty catalog as it does today,
  and logs the error.

## 7. Reviewer credit and exclusion

- The README gets a **Credits** section naming AutoEq and the reviewers whose measurements are included, with links.
  Data & Settings shows the same line.
- `config/snapshot-exclude-sources.txt` (empty to start) feeds `excludeSources` in both the snapshot builder and the
  manifest. Adding a reviewer there removes their measurements from the next release and stops daily imports of
  them. It doesn't delete curves already in users' catalogs.

## 8. Testing

- **`SeedInstaller`:** copies when there is no catalog, never overwrites an existing one, and leaves no partial file
  behind.
- **`Updater`** (`StubServer`, temp DB):
  - applies only unapplied files and records them
  - skips a file with a bad `sha256`
  - is offline-safe
  - takes the backup and prunes to three
  - shows the version notice only for a newer version
- **AutoEq dedupe:** importing the same entries twice adds no curves the second time.
- **`RssReader`:** parses saved sample RSS/Atom feeds from XenForo, Reddit and YouTube.
- **`FeedCollector`:** fixture feeds plus fixture gear names give the expected feed file, with ambiguous titles
  dropped and repeats from the 14-day window removed.
- **`SnapshotBuilder`:** with stubbed AutoEq and a stub manifest, produces a database with the expected gear, curves
  and `applied_update` rows.
- **`ResearchFilesTest`:** also covers `feeds/*.json` fixtures.
- **Manual, before release:** run `daily-feed.yml` and the package job with `workflow_dispatch`, install the `.msi`
  on a clean profile, and confirm the full catalog and a successful first update.

## Out of scope

- Installing app updates automatically. The app only notifies.
- Prices.
- Code signing.
- Reddit API access with keys.
- Running Claude in the cloud.
- Removing already-imported data when a reviewer is excluded.

## Risks

- Reddit may block GitHub's runners. It's best-effort, and forums and YouTube carry the feed.
- AutoEq or forum feed formats may change. Collector failures are visible in the Actions log and don't affect apps.
- The installer grows by roughly 40 MB (the uncompressed database) as the catalog grows. That's acceptable.
- Title matching can miss or mis-link threads. Only unambiguous matches are kept, and feed links are stored as
  `AUTO` (section 5), so they are told apart from curated research links.
