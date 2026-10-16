package audiogear.updates;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What one daily update did. skippedReason is set when nothing was attempted (offline, another update running);
 * newerVersion is the release to announce, or null; backup is the copy taken before changes, or null.
 */
public record UpdateResult(int files, int threads, int measurements, Manifest.LatestApp newerVersion,
                           Path backup, List<String> problems, String skippedReason) {

    public UpdateResult {
        problems = problems == null ? List.of() : List.copyOf(problems);
    }

    static UpdateResult skipped(String reason) {
        return new UpdateResult(0, 0, 0, null, null, List.of(), reason);
    }

    public boolean changedCatalog() { return files > 0 || measurements > 0; }

    public String summary() {
        if (skippedReason != null) return "Update skipped: " + skippedReason;
        List<String> parts = new ArrayList<>();
        if (measurements > 0) parts.add("+" + measurements + " measurements");
        if (threads > 0) parts.add("+" + threads + " threads");
        return parts.isEmpty() ? "Up to date" : "Updated: " + String.join(", ", parts);
    }
}
