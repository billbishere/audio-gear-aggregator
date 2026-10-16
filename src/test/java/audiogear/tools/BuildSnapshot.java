package audiogear.tools;

import audiogear.importers.AutoEqClient;
import audiogear.importers.Progress;
import audiogear.net.Http;
import audiogear.snapshot.SnapshotBuilder;
import audiogear.updates.UpdateFeed;
import audiogear.updates.UpdateResult;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Builds the installer's full catalog. Usage: BuildSnapshot &lt;out catalog.db&gt; [data base URL] [minimum gear, default 3000].
 * AutoEq calls use GITHUB_TOKEN when set (CI sets it; without one GitHub allows 60 listing requests an hour).
 */
public final class BuildSnapshot {
    private BuildSnapshot() {}

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        String base = args.length > 1 ? args[1] : UpdateFeed.DEFAULT_BASE;
        int minGear = args.length > 2 ? Integer.parseInt(args[2]) : 3000;
        Http http = new Http();
        String token = System.getenv("GITHUB_TOKEN");
        AutoEqClient autoEq = AutoEqClient.github(http, () -> token, Path.of("build", "autoeq-cache"));
        Progress progress = new Progress() {
            private long last;
            @Override public void update(long done, long total, String message) {
                long now = System.currentTimeMillis();
                if (now - last > 5000 || (total > 0 && done == total)) {
                    System.out.println("  " + (total > 0 ? done + "/" + total + " " : "") + (message == null ? "" : message));
                    last = now;
                }
            }
            @Override public boolean isCancelled() { return false; }
        };
        SnapshotBuilder.Result built = SnapshotBuilder.build(out, new UpdateFeed(http, base), autoEq, progress);
        UpdateResult r = built.update();
        r.problems().forEach(p -> System.out.println("PROBLEM: " + p));
        System.out.println("Snapshot " + out + ": " + built.gear() + " gear, " + r.measurements() + " measurements, "
                + r.files() + " update files, " + r.threads() + " threads, " + Files.size(out) / (1024 * 1024) + " MB");
        if (built.gear() < minGear) {
            System.out.println("ERROR: only " + built.gear() + " gear; a full catalog has at least " + minGear + ". Not shipping it.");
            System.exit(1);
        }
    }
}
