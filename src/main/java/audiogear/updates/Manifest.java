package audiogear.updates;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * The daily update index published on the repository's data branch: the newest app release, reviewers whose
 * measurements are left out, and every feed/research file (oldest first) with its SHA-256.
 */
public record Manifest(String generated, LatestApp latestApp, List<String> excludeSources, List<FileEntry> files) {

    public record LatestApp(String version, String url) {}

    /** name is the file's path on the data branch and its identity: once applied, never applied again. */
    public record FileEntry(String name, String kind, String sha256) {}

    public Manifest {
        excludeSources = excludeSources == null ? List.of() : List.copyOf(excludeSources);
        files = files == null ? List.of() : List.copyOf(files);
    }

    static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static Manifest parse(String json) {
        try {
            return JSON.readValue(json, Manifest.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Unreadable update manifest: " + e.getMessage(), e);
        }
    }
}
