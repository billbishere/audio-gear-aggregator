# Full-Data Installer and Daily Updates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a CI-built full catalog in the installer, publish a key-less daily thread feed from GitHub Actions, and
have the app apply new feed and research files, new AutoEq measurements and a new-version notice every day.

**Architecture:** One `Updater` holds all the update logic, and both the app and the snapshot builder run it. That
means the snapshot is just "an empty database plus one update", built with the same code paths users run daily. A
daily workflow runs a `DailyFeed` tool that turns forum, Reddit and YouTube RSS into research-format feed files. It
publishes them with a `manifest.json` to the `data` branch, which the app reads over raw.githubusercontent.com.

**Tech Stack:** Java 25, JavaFX 27, Gradle Kotlin DSL, SQLite via JDBI, Jackson, JUnit 6, GitHub Actions, jpackage
with WiX 5.

**Spec:** `docs/superpowers/specs/2026-10-16-snapshot-and-daily-updates-design.md`

## Global Constraints

- No new dependencies. Use the JDK (`javax.xml`, `java.security.MessageDigest`, `java.nio.channels.FileLock`), Jackson and JDBI.
- Data base URL: `https://raw.githubusercontent.com/billbishere/audio-gear-aggregator/data/`.
- Updates only add data or fill empty fields. They never overwrite or delete.
- Update backups are named `catalog-update-YYYYMMDD-HHMMSS.db`, and only the newest 3 of that prefix are kept. Other backups are never pruned.
- An update is due when `updates.lastRun` is unset or at least 20 hours old. The app checks hourly and runs when due. `updates.daily` defaults to `true`.
- Feed files are `feeds/YYYY-MM-DD.json` in research-file format with `window.from/to`, and threads have no `snippet`.
- Feed matching: a title is kept when `GearNameMatcher` matches it to **1 or 2** known gear. 0 or 3 or more is dropped. (This is the plan's definition of the spec's "unambiguous": a two-item comparison thread is kept.)
- Thread URLs already in feed files from the last 14 days are dropped.
- The daily workflow runs at 06:00 UTC plus `workflow_dispatch`, and needs only `GITHUB_TOKEN`.
- Excluded reviewers are listed in `config/snapshot-exclude-sources.txt`, one per line, with `#` comments.
- Feed threads are linked as `LinkKind.AUTO`, research threads as `LinkKind.MANUAL`.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **Never** run the app, `gradlew run` or `importResearch` against the user's real data folder from Claude's shell. Claude Desktop sandboxes `%APPDATA%`. For manual checks, use `explorer.exe <script.cmd>` with `JAVA_TOOL_OPTIONS=-Daudiogear.home=<temp dir>`.
- Git Bash rewrites `rev:path` arguments, so prefix those git commands with `MSYS_NO_PATHCONV=1`. In PowerShell, use `gradlew.bat`.

## Review Focus

1. **Two app windows open at once.** The user has done this. Both run the daily update, but only one may apply it. The other reports "another update is running" (Task 4: `secondConcurrentRunIsSkipped`).
2. **A file in the manifest returns 404** (deleted from `data`). That file is skipped and not recorded, and the other files still apply (Task 4: `missingFileIsSkippedOthersApplied`).
3. **Fresh Windows profile with no `%APPDATA%\AudioGearAggregator` folder.** The seed copy creates the folder (Task 5: `copiesIntoMissingFolder`).
4. **A feed containing a DOCTYPE or external entity** (XXE). It's rejected, and no local file is read (Task 7: `rejectsDoctype`).
5. **Version 1.10.0 compared with 1.9.0.** It's newer, compared numerically rather than as text (Task 3: `comparesVersionsNumerically`).

---

### Task 1: Stop AutoEq import from adding duplicate curves (+ migration V3)

**Files:**
- Create: `src/main/resources/audiogear/db/migration/V3__updates.sql`
- Modify: `src/main/java/audiogear/db/Migrator.java` (`MIGRATIONS` list)
- Modify: `src/main/java/audiogear/db/CurveRepository.java`
- Modify: `src/main/java/audiogear/importers/AutoEqImporter.java` (`importEntries`)
- Test: `src/test/java/audiogear/importers/AutoEqDedupeTest.java`

**Interfaces:**
- Produces: table `applied_update` (used by Task 2), `CurveRepository.origins(): Set<String>`, and an
  `AutoEqImporter.importEntries` that skips entries whose `client.rawUrl(e.path())` is already a curve origin. Those
  entries are counted neither as imported nor as skipped.

- [ ] **Step 1: Write the failing test.** Build the stub AutoEq server the same way `ResearchImporterTest` does: one source with two over-ear CSVs.

```java
@Test void importingTheSameEntriesTwiceAddsNoCurves() {
    ImportSummary first = importer.importEntries(entries, Progress.NONE);
    ImportSummary second = importer.importEntries(entries, Progress.NONE);
    assertEquals(2, first.imported());
    assertEquals(0, second.imported());
    assertEquals(2, curves.byKind(CurveKind.FR).size());
}
@Test void migrationCreatesAppliedUpdateTable() {
    assertEquals(1, db.jdbi().withHandle(h -> h.createQuery(
        "SELECT count(*) FROM sqlite_master WHERE name = 'applied_update'").mapTo(Integer.class).one()));
}
```

- [ ] **Step 2: Run the test and confirm it fails.** Run `./gradlew test --tests audiogear.importers.AutoEqDedupeTest`. Expected: FAIL, with `second.imported()` equal to 2 and the table missing.
- [ ] **Step 3: Add the migration.** Create `V3__updates.sql` and add `"V3__updates.sql"` to `MIGRATIONS`:

```sql
CREATE TABLE applied_update (
    name       TEXT PRIMARY KEY,
    kind       TEXT NOT NULL,
    applied_at TEXT NOT NULL
);
CREATE INDEX curve_origin ON curve (origin);
```

- [ ] **Step 4: Implement the dedupe.** Add `Set<String> origins()` to `CurveRepository`, as `SELECT origin FROM curve WHERE origin IS NOT NULL`. In `importEntries`, filter the entries against it before submitting downloads.
- [ ] **Step 5: Run the whole suite and confirm it passes.** Run `./gradlew test`. Expected: BUILD SUCCESSFUL. Existing upgrade tests must still pass with V3.
- [ ] **Step 6: Commit** with message `Skip AutoEq files already imported; add applied_update table (V3)`.

### Task 2: Applied-update record and link kind for research imports

**Files:**
- Create: `src/main/java/audiogear/db/AppliedUpdateRepository.java`
- Modify: `src/main/java/audiogear/importers/ResearchImporter.java`
- Test: `src/test/java/audiogear/db/AppliedUpdateRepositoryTest.java`, and add to `src/test/java/audiogear/importers/ResearchImporterTest.java`

**Interfaces:**
- Consumes: the `applied_update` table from Task 1.
- Produces: `new AppliedUpdateRepository(Database db)` with `Set<String> names()` and `void record(String name, String kind)`. `record` does `INSERT OR IGNORE`, with `applied_at = Instant.now().toString()`.
- Produces: `ResearchImporter.importJson(Reader reader, Progress progress, LinkKind linkKind): Result`. The existing `importJson(Reader, Progress)` delegates with `LinkKind.MANUAL`.

- [ ] **Step 1: Write the failing tests.**
  - `recordIsIdempotent`: calling `record("feeds/2026-10-17.json", "feed")` twice leaves `names()` equal to that one name.
  - `feedImportLinksThreadsAsAuto`: import a one-item JSON with `LinkKind.AUTO`. Then `SELECT kind FROM thread_gear` returns only `"AUTO"`.
- [ ] **Step 2: Run them and confirm they fail.** Run `./gradlew test --tests "audiogear.db.AppliedUpdateRepositoryTest" --tests "audiogear.importers.ResearchImporterTest"`. Expected: compile failure.
- [ ] **Step 3: Implement the repository and the overload.** In `importJson`, use `linkKind` where it currently passes `LinkKind.MANUAL`. Leave the cross-link pass as `AUTO`.
- [ ] **Step 4: Run the suite and confirm it passes.** Run `./gradlew test`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** with message `Record applied update files; let research imports choose the link kind`.

### Task 3: Update feed client, manifest and version comparison

**Files:**
- Create: `src/main/java/audiogear/updates/Manifest.java`, `UpdateFeed.java`, `Versions.java` and `AppVersion.java`
- Modify: `build.gradle.kts`, adding `tasks.jar { manifest { attributes("Implementation-Version" to project.version) } }`
- Test: `src/test/java/audiogear/updates/UpdateFeedTest.java`, `VersionsTest.java`

**Interfaces:**
- Produces: `record Manifest(String generated, LatestApp latestApp, List<String> excludeSources, List<FileEntry> files)`, with nested `record LatestApp(String version, String url)` and `record FileEntry(String name, String kind, String sha256)`. Also `static Manifest parse(String json)`, using Jackson and ignoring unknown fields, with a null `excludeSources` or `files` becoming an empty list.
- Produces: `new UpdateFeed(Http http, String baseUrl)` and `static final String DEFAULT_BASE` (the Global Constraints URL).
  - `Manifest manifest() throws IOException` reads `baseUrl + "manifest.json"`.
  - `String download(Manifest.FileEntry f) throws IOException` reads `baseUrl + f.name()`. It throws `IOException("checksum mismatch for " + name)` when the lowercase-hex SHA-256 of the UTF-8 bytes differs from `f.sha256()`.
- Produces: `Versions.isNewer(String candidate, String current): boolean` compares dot-separated numbers and is `false` when either side isn't numeric.
- Produces: `AppVersion.current(): String`. It tries the `jpackage.app-version` system property, then the jar's `Implementation-Version`, then returns `"dev"`.

- [ ] **Step 1: Write the failing tests.**

```java
@Test void comparesVersionsNumerically() {
    assertTrue(Versions.isNewer("1.10.0", "1.9.0"));
    assertTrue(Versions.isNewer("1.2", "1.1.9"));
    assertFalse(Versions.isNewer("1.1.0", "1.1.0"));
    assertFalse(Versions.isNewer("2.0.0", "dev"));
}
```

  `UpdateFeedTest` uses `StubServer`:
  - `readsManifest`: the files, `latestApp.version()` and `excludeSources` are parsed.
  - `downloadVerifiesChecksum`: the right hash returns the body.
  - `rejectsChecksumMismatch`: the message contains `"checksum mismatch"`.
  - `missingFileThrowsHttpStatus`: a 404 gives `Http.HttpStatusException` with `status() == 404`.
- [ ] **Step 2: Run them and confirm they fail.** Run `./gradlew test --tests "audiogear.updates.*"`. Expected: compile failure.
- [ ] **Step 3: Implement the four classes.**
- [ ] **Step 4: Run the tests and confirm they pass.** Run `./gradlew test --tests "audiogear.updates.*"`. Expected: PASS.
- [ ] **Step 5: Commit** with message `Add update manifest, feed client and version comparison`.

### Task 4: Updater

**Files:**
- Create: `src/main/java/audiogear/updates/Updater.java`, `UpdateResult.java`
- Modify: `src/main/java/audiogear/db/Database.java`, adding `backupNow(Path backupDir, String prefix)`. The existing `backupNow(Path)` delegates with `"catalog-"`.
- Test: `src/test/java/audiogear/updates/UpdaterTest.java`

**Interfaces:**
- Consumes:
  - `UpdateFeed` and `Manifest` (Task 3)
  - `AppliedUpdateRepository` (Task 2)
  - `ResearchImporter.importJson(Reader, Progress, LinkKind)` (Task 2), constructed with `autoEq = null` and `autoEqImporter = null`
  - `CurveRepository.origins()` and the dedupe in `AutoEqImporter.importEntries` (Task 1)
- Produces:

```java
public Updater(Database db, Path backupDir /* null = no backups */, UpdateFeed feed,
               AutoEqClient autoEq /* null = skip measurements */, AutoEqImporter autoEqImporter, String currentVersion)
public UpdateResult run(Progress progress)   // never throws for network errors
static void pruneBackups(Path dir, String prefix, int keep) throws IOException
public record UpdateResult(int files, int threads, int measurements, Manifest.LatestApp newerVersion,
                           Path backup, List<String> problems, String skippedReason) {
    public String summary()  // "Up to date" or "Updated: +12 measurements, +40 threads" (omit zero parts)
}
```

- **`run` order:**
  1. `tryLock` on `db.file().resolveSibling("update.lock")`. If it isn't acquired, set `skippedReason = "another update is running"`.
  2. Fetch the manifest. On an `IOException`, `skippedReason` is the message.
  3. Pending files are those whose `name` isn't in `names()`.
  4. Pending AutoEq entries come from `sources()` minus `excludeSources`, then `entries(s)`, then those whose raw URL isn't in `origins()`. On an `IOException`, add it to `problems` and treat measurements as 0 pending.
  5. If anything is pending and `backupDir != null`, call `db.backupNow(backupDir, "catalog-update-")`, then `pruneBackups(backupDir, "catalog-update-", 3)`.
  6. Apply the files oldest first: `download`, then import with `AUTO` for kind `feed` and `MANUAL` otherwise, then `record`. On any exception, add it to `problems` and continue. Stop between files once `progress.isCancelled()`, and skip step 7 in that case.
  7. Run `importEntries(pending)`.
  8. Set `newerVersion` when `Versions.isNewer(latestApp.version, currentVersion)`.

- [ ] **Step 1: Write the failing tests** (`StubServer`, temp DB, a stub manifest with a correct SHA-256 computed in the test):
  - `appliesPendingFilesOnceAndRecordsThem`: the first run gives `files == 2` and `threads > 0`. The second run gives `files == 0` and `summary()` equal to `"Up to date"`.
  - `skipsFileWithBadChecksumAndRetriesLater`: `files == 0`, the problem mentions `"checksum"`, and the file isn't in `names()`.
  - `missingFileIsSkippedOthersApplied`: a 404 for one file and 200 for another give `files == 1` and one problem.
  - `offlineReturnsSkippedWithoutChanges`: with `baseUrl` pointing at a closed port, `skippedReason != null` and no backup file.
  - `backsUpBeforeChangesAndKeepsThree`: with 4 older `catalog-update-*.db` files present, exactly 3 remain after the run, including `result.backup()`. A second run that finds nothing pending creates no new backup.
  - `reportsNewerVersionOnlyWhenNewer`: `latestApp` "1.2.0" with current "1.1.0" is non-null. With current "1.2.0" it's null.
  - `secondConcurrentRunIsSkipped`: while the test holds a `FileLock` on `update.lock`, `skippedReason` is `"another update is running"`.
  - `importsOnlyNewAutoEqMeasurementsAndHonoursExclusions`: stub AutoEq has sources A and B, and the manifest has `excludeSources ["B"]`. The first run's `measurements` count equals A's entries. The second run's is 0.
- [ ] **Step 2: Run them and confirm they fail.** Run `./gradlew test --tests audiogear.updates.UpdaterTest`. Expected: compile failure.
- [ ] **Step 3: Implement `Updater`, `UpdateResult` and the `backupNow` overload.**
- [ ] **Step 4: Run the suite and confirm it passes.** Run `./gradlew test`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** with message `Add the daily Updater: feed/research files, new measurements, version notice`.

### Task 5: Seed installer and startup wiring

**Files:**
- Create: `src/main/java/audiogear/updates/SeedInstaller.java`
- Modify: `src/main/java/audiogear/app/AudioGearApp.java` (in `start`, before `Database.open`)
- Test: `src/test/java/audiogear/updates/SeedInstallerTest.java`

**Interfaces:**
- Produces: `static Optional<Path> bundledSeed()`. It checks `<folder of the jar holding SeedInstaller>/seed/catalog.db`, then `<that folder's parent>/seed/catalog.db`, and returns the first that exists.
- Produces: `static boolean installIfMissing(Path seed, Path database) throws IOException`. It returns `false` when `database` exists. Otherwise it runs `createDirectories(parent)`, copies to `catalog.db.seed-tmp`, then does `Files.move(..., ATOMIC_MOVE)`. On failure it deletes the tmp file and rethrows.

- [ ] **Step 1: Write the failing tests.**
  - `copiesIntoMissingFolder`: the target is `tmp/fresh/AudioGearAggregator/catalog.db`, a folder that doesn't exist yet. The result is `true` and the bytes equal the seed's.
  - `neverOverwritesExisting`: an existing database keeps its content and the result is `false`.
  - `leavesNoPartialFileOnFailure`: a missing seed throws `IOException`, and neither `catalog.db` nor `catalog.db.seed-tmp` exists afterwards.
- [ ] **Step 2: Run them and confirm they fail.** Run `./gradlew test --tests audiogear.updates.SeedInstallerTest`. Expected: compile failure.
- [ ] **Step 3: Implement it and wire up startup.** In `AudioGearApp.start`, after `Logging.init`, call `SeedInstaller.bundledSeed().ifPresent(...)` to run `installIfMissing(seed, paths.database())`. Log `INFO "Installed bundled catalog from " + seed` on `true`, and `SEVERE` on `IOException` (the app continues).
- [ ] **Step 4: Run the suite and confirm it passes.** Run `./gradlew test`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** with message `Install the bundled catalog on first launch`.

### Task 6: Daily scheduling, settings and UI

**Files:**
- Create: `src/main/java/audiogear/updates/UpdateSchedule.java`
- Modify:
  - `src/main/java/audiogear/settings/Settings.java`
  - `src/main/java/audiogear/app/AppContext.java`
  - `src/main/java/audiogear/app/StatusModel.java`
  - `src/main/java/audiogear/ui/MainWindow.java` (`statusBar`)
  - `src/main/java/audiogear/ui/views/DataView.java` (settings section)
  - `src/main/java/audiogear/app/AudioGearApp.java`
- Test: `src/test/java/audiogear/updates/UpdateScheduleTest.java`, plus `src/test/java/audiogear/settings/SettingsTest.java` (create if absent)

**Interfaces:**
- Consumes: `Updater` and `UpdateResult` (Task 4), `UpdateFeed` and `Manifest.LatestApp` (Task 3), `AppVersion.current()`.
- Produces: `UpdateSchedule.isDue(Instant lastRun, Instant now)`, true when `lastRun == null || Duration.between(lastRun, now) >= DUE_AFTER`. Also `DUE_AFTER = Duration.ofHours(20)` and `CHECK_EVERY = Duration.ofHours(1)`.
- Produces in `Settings`:
  - `boolean updatesDaily()` (default `true`) and `setUpdatesDaily(boolean)`
  - `Instant updatesLastRun()` (null when unset) and `setUpdatesLastRun(Instant)`
  - `String updatesDismissedVersion()` and `setUpdatesDismissedVersion(String)`
  - `String updatesBaseUrl()` (default `UpdateFeed.DEFAULT_BASE`)

  The keys are `updates.daily`, `updates.lastRun`, `updates.dismissedVersion` and `updates.baseUrl`.
- Produces: `StatusModel.newVersion: ObjectProperty<Manifest.LatestApp>`.
- Produces in `AppContext`:
  - `void startDailyUpdates()`: a single-thread `ScheduledExecutorService` that checks after 10 s, then every `CHECK_EVERY`. It calls `updateNow()` when `updatesDaily()` and `isDue`.
  - `void updateNow()`: runs `runWithProgress("Updating catalog", updater::run, result -> ...)`. On done, when `skippedReason == null`, it saves `setUpdatesLastRun(Instant.now())`. It shows `status.info(result.summary())`, calls `reloadCatalog()` when anything changed, and sets `status.newVersion` when `newerVersion` isn't equal to `updatesDismissedVersion()`. It ignores the call if an update is already running in this window.
- UI copy:
  - DataView checkbox **Check for updates daily** and button **Update now**.
  - Status bar label `"Version " + v + " is available"`, with a **Download** button (`HostServices.showDocument(url)`) and a **Dismiss** button that saves `setUpdatesDismissedVersion(v)` and clears the property.

- [ ] **Step 1: Write the failing tests.**
  - `UpdateScheduleTest`: `isDue(null, now)` is true, 19 h is false, and 20 h is true.
  - `SettingsTest`: the defaults are `updatesDaily() == true` and `updatesLastRun() == null`. All four keys survive `save()` followed by `Settings.load`.
- [ ] **Step 2: Run them and confirm they fail.** Run `./gradlew test --tests "audiogear.updates.UpdateScheduleTest" --tests "audiogear.settings.SettingsTest"`. Expected: compile failure.
- [ ] **Step 3: Implement the settings, the schedule and the `AppContext` methods.** Call `ctx.startDailyUpdates()` in `AudioGearApp.start` after the window is shown.
- [ ] **Step 4: Add the DataView controls and the status-bar notice.**
- [ ] **Step 5: Run the suite and confirm it passes.** Run `./gradlew test`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 6: Check the screens visually.** Run `./gradlew screenshots -Pout=build/screenshots`. Expected: the Data & Settings screenshot shows the checkbox and the button. This uses a throwaway data folder and makes no network update, because `updates.daily` doesn't trigger without a running app.
- [ ] **Step 7: Commit** with message `Run the updater daily; settings, Update now and new-version notice`.

### Task 7: RSS and Atom reader

**Files:**
- Create: `src/main/java/audiogear/feed/RssReader.java`, `FeedItem.java`
- Create fixtures: `src/test/resources/audiogear/feed/xenforo.rss`, `reddit.atom`, `youtube.atom`. Hand-written, with invented titles; don't copy real posts.
- Test: `src/test/java/audiogear/feed/RssReaderTest.java`

**Interfaces:**
- Produces: `record FeedItem(String url, String title, String published /* yyyy-MM-dd UTC or null */)`.
- Produces: `static List<FeedItem> RssReader.parse(String xml) throws IOException`.
  - RSS 2.0 reads `item/link`, `title` and `pubDate` (RFC 1123).
  - Atom reads `entry/link[@rel='alternate' or no rel]/@href`, `title` and `published`, falling back to `updated` (ISO-8601).
  - The DOM parser has `FEATURE_SECURE_PROCESSING` and `http://apache.org/xml/features/disallow-doctype-decl` set to true. Any parser error becomes an `IOException`.

- [ ] **Step 1: Write the failing tests.**
  - `parsesXenForoRss`: 2 items, and the first has a published date of `"2026-10-16"` from `Thu, 16 Oct 2026 14:05:00 +0000`.
  - `parsesRedditAtom`: the `href` link and the date from `updated`.
  - `parsesYouTubeAtom`: the date from `published`.
  - `itemWithoutDateHasNullPublished`.
  - `rejectsDoctype`: a feed declaring `<!DOCTYPE x [<!ENTITY e SYSTEM "file:///c:/windows/win.ini">]>` throws `IOException`.
- [ ] **Step 2: Run them and confirm they fail.** Run `./gradlew test --tests audiogear.feed.RssReaderTest`. Expected: compile failure.
- [ ] **Step 3: Implement the reader.**
- [ ] **Step 4: Run the tests and confirm they pass.** Run the same command. Expected: PASS.
- [ ] **Step 5: Commit** with message `Add a hardened RSS/Atom reader for the daily feed`.

### Task 8: Feed collector

**Files:**
- Create: `src/main/java/audiogear/feed/FeedSource.java`, `KnownGear.java`, `GearIndex.java`, `FeedFile.java`, `FeedCollector.java`
- Test: `src/test/java/audiogear/feed/FeedCollectorTest.java`

**Interfaces:**
- Consumes: `RssReader` (Task 7), `GearNameMatcher(List<GearRepository.GearRef>)`, `match(String) -> Set<Long>`, `UrlNormalizer.normalize`, `BrandParser`, and `AutoEqClient.sources()` and `entries()`.
- Produces:
  - `record FeedSource(String name, String url, ThreadSource source)`, with `static List<FeedSource> load(Path json)` reading `{"sources":[...]}`.
  - `record KnownGear(GearType type, String brand, String model)`.
  - `GearIndex.fromAutoEq(AutoEqClient client, Set<String> excludeSources, BrandParser brands): List<KnownGear>`, where over-ear is `HEADPHONE` and everything else is `IEM`.
  - `GearIndex.fromResearch(Path researchDir): List<KnownGear>`.
  - `GearIndex.merge(List<KnownGear>...)`, which dedupes on brand plus model, case-insensitively.
  - `record FeedFile(String title, String gathered, Window window, String method, List<ResearchImporter.ResearchItem> items)` with `record Window(String from, String to)`.
  - `new FeedCollector(Http http, List<FeedSource> sources, List<KnownGear> gear)` with `FeedFile collect(LocalDate today, LocalDate windowFrom, Set<String> seenUrls, List<String> log) throws IOException`. It throws when **every** source fails, and logs each failure otherwise. It keeps items with `published == null || published >= windowFrom`, drops normalized URLs in `seenUrls`, keeps 1–2 gear matches, and groups threads by gear. Each thread is `ResearchThread(url, title, source.name() of ThreadSource, published, null)`.
  - `static Set<String> FeedCollector.seenUrls(Path feedsDir, LocalDate today)`: URLs in `feeds/*.json` whose filename date is within the 14 days before `today`.
  - `static LocalDate FeedCollector.windowFrom(Path feedsDir, LocalDate today)`: the newest file's `window.to`, or `today.minusDays(1)` when there isn't one.

- [ ] **Step 1: Write the failing tests.** `StubServer` serves the Task 7 fixtures, plus a 500 for a fourth source. The gear list is HD 600, HD 650 and Edition XS.
  - `collectsMatchedThreadsGroupedByGear`: "HD 600 vs HD 650" appears under both, and an unmatched title is absent.
  - `dropsTitlesMatchingThreeOrMoreGear`.
  - `dropsSeenUrlsAndItemsBeforeWindow`.
  - `oneFailingSourceIsLoggedOthersContinue`: the log has 1 entry.
  - `allSourcesFailingThrows`.
  - `windowFromUsesPreviousFileOrYesterday`.
  - `outputImportsCleanly`: write the `FeedFile` with Jackson, import it with `ResearchImporter` into a temp DB with `LinkKind.AUTO`, and get no problems and `threads() > 0`.
- [ ] **Step 2: Run them and confirm they fail.** Run `./gradlew test --tests audiogear.feed.FeedCollectorTest`. Expected: compile failure.
- [ ] **Step 3: Implement the five types.**
- [ ] **Step 4: Run the suite and confirm it passes.** Run `./gradlew test`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** with message `Add the daily feed collector`.

### Task 9: Manifest writer, `DailyFeed` tool and source lists

**Files:**
- Create: `src/main/java/audiogear/feed/ManifestWriter.java`
- Create: `src/test/java/audiogear/tools/DailyFeed.java`. It's a CLI, like `ImportResearch`.
- Create: `feeds/sources.json`, `config/snapshot-exclude-sources.txt` (a comment header only)
- Modify: `build.gradle.kts`, adding a `dailyFeed` JavaExec task modelled on `importResearch`
- Test: `src/test/java/audiogear/feed/ManifestWriterTest.java`

**Interfaces:**
- Consumes: `FeedCollector`, `GearIndex` and `FeedSource` (Task 8), `Manifest` (Task 3).
- Produces:
  - `ManifestWriter.write(Path dataDir, Path researchDir, Manifest.LatestApp latest, List<String> excludeSources, Instant now) throws IOException`. It copies `researchDir/*.json` to `dataDir/research/` and writes `dataDir/manifest.json`. `files` lists the research files (sorted), then `feeds/*.json` (sorted), each with lowercase-hex SHA-256 of its bytes.
  - `ManifestWriter.excludeSources(Path file): List<String>`, which trims lines and skips blanks and `#` lines.
- **`DailyFeed` arguments:** `--data <dir> --sources feeds/sources.json --research research --exclude config/snapshot-exclude-sources.txt --date yyyy-MM-dd --latest-version V --latest-url U`. It authenticates AutoEq calls with `GITHUB_TOKEN` from the environment. It writes `<data>/feeds/<date>.json` (pretty JSON) only when the feed has at least one item, then always calls `ManifestWriter.write`, which refreshes `generated`.
- **Gradle `dailyFeed`:** maps `-Pdata`, `-Pdate` (default today in UTC), `-PlatestVersion` and `-PlatestUrl` to those arguments.
- **`feeds/sources.json`** covers:
  - ASR forums 58 (headphone and IEM reviews), 50 (headphone amp reviews) and 53 (combination DAC/amp), each as `https://www.audiosciencereview.com/forum/index.php?forums/<slug>.<id>/index.rss`
  - the Head-Fi full-size headphone, IEM and source-component forum feeds
  - the SBAF site feed
  - `https://www.reddit.com/r/{headphones,HeadphoneAdvice,iems}/.rss`
  - YouTube `feeds/videos.xml?channel_id=` for Z Reviews, DMS, Crinacle, Resolve (The Headphone Show), Joshua Valour and Passion for Sound

- [ ] **Step 1: Write the failing tests.**
  - `writesResearchThenFeedsWithChecksums`: the SHA-256 values match `MessageDigest` over the file bytes, and the output round-trips through `Manifest.parse`.
  - `copiesResearchFiles`.
  - `excludeSourcesIgnoresCommentsAndBlanks`.
  - `writtenFilesPassUpdateFeedChecksum`: serve `dataDir` from `StubServer`, and `UpdateFeed.download` returns each file without error.
- [ ] **Step 2: Run them and confirm they fail.** Run `./gradlew test --tests audiogear.feed.ManifestWriterTest`. Expected: compile failure.
- [ ] **Step 3: Implement `ManifestWriter`, `DailyFeed` and the Gradle task.**
- [ ] **Step 4: Fill in `feeds/sources.json` with verified URLs only.** For each URL, use `curl -sL -A "AudioGearAggregator/1.0" <url> | head -c 300` and keep it only if the response is RSS or Atom. Look up YouTube channel IDs from each channel page's RSS link. Drop any source that doesn't respond and list the drops in the commit message.
- [ ] **Step 5: Do a local dry run into a throwaway folder.** Run `./gradlew dailyFeed -Pdata=build/data-dry -PlatestVersion=1.1.0 -PlatestUrl=https://github.com/billbishere/audio-gear-aggregator/releases/tag/v1.1.0`. Expected: `build/data-dry/feeds/<today>.json` and `manifest.json` exist, and `./gradlew test` still passes.
- [ ] **Step 6: Commit** with message `Add DailyFeed tool, manifest writer and feed source list`.

### Task 10: Daily GitHub Actions workflow

**Files:**
- Create: `.github/workflows/daily-feed.yml`

**Interfaces:**
- Consumes: the `dailyFeed` Gradle task (Task 9).
- Produces: a `data` branch holding `manifest.json`, `feeds/*.json` and `research/*.json`, updated daily.

- [ ] **Step 1: Write the workflow.**
  - Trigger: `on: schedule: - cron: '0 6 * * *'` plus `workflow_dispatch`.
  - `permissions: contents: write` and `concurrency: daily-feed`.
  - Runner: `ubuntu-latest` with `actions/checkout@v7`, `actions/setup-java@v6` (microsoft, 25) and `gradle/actions/setup-gradle@v6`.
  - Steps, in order:
    1. Check out the `data` branch into `data/`. If `git ls-remote --heads origin data` is empty, create an orphan branch there instead.
    2. Read `gh release view --json tagName,url`, stripping the leading `v` for the version, with `GH_TOKEN: ${{ github.token }}`.
    3. Run `./gradlew dailyFeed -Pdata=data -PlatestVersion=... -PlatestUrl=...` with `GITHUB_TOKEN: ${{ github.token }}`.
    4. In `data/`, configure `github-actions[bot]`, then run `git add -A`. Commit `Daily feed <date>` only if anything is staged, and push to `origin HEAD:data`.
- [ ] **Step 2: Lint it.** Run `actionlint .github/workflows/daily-feed.yml` if it's available. Otherwise check that `python -c "import yaml,sys; yaml.safe_load(open(sys.argv[1]))" .github/workflows/daily-feed.yml` exits 0.
- [ ] **Step 3: Commit** with message `Add the daily feed workflow (publishes to the data branch)`. It's verified after merge, in Task 12.

### Task 11: Snapshot builder, packaging and reviewer credits

**Files:**
- Create: `src/main/java/audiogear/snapshot/SnapshotBuilder.java`, `src/test/java/audiogear/tools/BuildSnapshot.java`
- Modify:
  - `build.gradle.kts`: add a `buildSnapshot` JavaExec task that writes `build/snapshot/seed/catalog.db`. `packageApp` and `packageInstaller` append `"--app-content", <build/snapshot/seed>` when `build/snapshot/seed/catalog.db` exists.
  - `.github/workflows/ci.yml`: in the `package` job, add a `Build catalog snapshot` step running `.\gradlew.bat buildSnapshot` with `GITHUB_TOKEN`, before packaging.
  - `README.md`: add a Credits section, plus install text saying the installer includes the full catalog and updates daily.
  - `src/main/java/audiogear/ui/views/DataView.java`: add a credits line, "Measurements by their reviewers, via AutoEq (github.com/jaakkopasanen/AutoEq)".
- Test: `src/test/java/audiogear/snapshot/SnapshotBuilderTest.java`

**Interfaces:**
- Consumes: `Updater` (Task 4), `UpdateFeed` (Task 3), `AutoEqImporter.importTargets()`.
- Produces: `static UpdateResult SnapshotBuilder.build(Path database, UpdateFeed feed, AutoEqClient autoEq, Progress progress) throws IOException`.
  1. Delete any existing file.
  2. `Database.open(database, <temp backups dir>)`.
  3. `new Updater(db, null, feed, autoEq, new AutoEqImporter(autoEq, gear, curves, BrandParser.standard()), "snapshot").run(progress)`.
  4. `importTargets()`.
  5. `VACUUM`.
- **`BuildSnapshot` arguments:** `<out.db> [baseUrl]`, with `GITHUB_TOKEN` taken from the environment.

- [ ] **Step 1: Write the failing test.** `snapshotHasNonExcludedMeasurementsResearchAndTargets` uses stub AutoEq sources A and B and a stub manifest with `excludeSources ["B"]` and one research file. Assert:
  - gear exists from A only, plus the research gear
  - 2 `TARGET` curves
  - `applied_update` contains the research file name
  - `PRAGMA integrity_check` returns `"ok"`
- [ ] **Step 2: Run it and confirm it fails.** Run `./gradlew test --tests audiogear.snapshot.SnapshotBuilderTest`. Expected: compile failure.
- [ ] **Step 3: Implement the builder, the CLI and the Gradle task.**
- [ ] **Step 4: Run the suite and confirm it passes.** Run `./gradlew test`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 5: Check the packaging locally.** Copy any small test catalog to `build/snapshot/seed/catalog.db`, run `./gradlew packageApp`, and find where the seed landed: `find "build/jpackage/Audio Gear Aggregator" -name catalog.db`. Expected: `app/seed/catalog.db` or `seed/catalog.db`, both of which `SeedInstaller.bundledSeed()` checks. If it's somewhere else, fix `bundledSeed()` and its test.
- [ ] **Step 6: Commit** with message `Build a full catalog snapshot in CI and ship it in the installer; credit reviewers`.

### Task 12: Release and end-to-end check

**Files:**
- Modify: `build.gradle.kts` (`version = "1.2.0"`)

- [ ] **Step 1: Bump the version, push the branch and open a PR.** Expected: CI `test` is green.
- [ ] **Step 2: Merge, then run the daily feed workflow.** Run `gh workflow run daily-feed.yml`. When the run is done, `MSYS_NO_PATHCONV=1 git show origin/data:manifest.json` should list `research/2026-10-most-discussed.json` and at least one `feeds/` file.
- [ ] **Step 3: Release.** Tag `v1.2.0` and push the tag. Expected: the release has the `.msi` and the portable zip, and the package job log shows the snapshot gear count (about 3,500 or more).
- [ ] **Step 4: Check a clean install outside Claude's sandbox.** Download the `.msi` and install it. Then run a `.cmd` through `explorer.exe` that sets `JAVA_TOOL_OPTIONS=-Daudiogear.home=%TEMP%\agg-clean` and starts the installed exe. Expected:
  - `%TEMP%\agg-clean\catalog.db` exists, and counting a copy shows about 3,500 or more gear
  - its log shows `Installed bundled catalog`, followed by a successful update summary
- [ ] **Step 5: Check the user's real catalog is untouched apart from additions,** using the explorer-script method. Expected:
  - the gear count is at least 3,500
  - the most-discussed items are still tagged
  - after the user opens the app once, `backups\catalog-update-*.db` exists and `updates.lastRun` is set
- [ ] **Step 6: Update memory** (`claude-sandbox-appdata.md` and `monthly-research-refresh.md`) with the data branch, the daily workflow and the v1.2.0 facts.
