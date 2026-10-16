package audiogear.feed;

import audiogear.updates.Manifest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/** Writes the data branch's manifest.json: research files (copied from main) then daily feeds, each with its SHA-256. */
public final class ManifestWriter {
    private ManifestWriter() {}

    public static void write(Path dataDir, Path researchDir, Manifest.LatestApp latest, List<String> excludeSources, Instant now)
            throws IOException {
        Path researchOut = Files.createDirectories(dataDir.resolve("research"));
        for (Path f : jsonFiles(researchDir)) {
            // Unix line endings, as GitHub serves them, so the checksum matches what apps download
            String text = Files.readString(f, StandardCharsets.UTF_8).replace("\r\n", "\n");
            Files.writeString(researchOut.resolve(f.getFileName().toString()), text, StandardCharsets.UTF_8);
        }
        List<Manifest.FileEntry> files = new ArrayList<>();
        for (Path f : jsonFiles(researchOut)) files.add(entry(dataDir, f, "research"));
        for (Path f : jsonFiles(dataDir.resolve("feeds"))) files.add(entry(dataDir, f, "feed"));

        Manifest m = new Manifest(now.toString(), latest, excludeSources, files);
        String json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(m).replace("\r\n", "\n");
        Files.writeString(dataDir.resolve("manifest.json"), json + "\n", StandardCharsets.UTF_8);
    }

    /** Reviewer names from config/snapshot-exclude-sources.txt: one per line, blank lines and # comments ignored. */
    public static List<String> excludeSources(Path file) throws IOException {
        if (!Files.exists(file)) return List.of();
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream().map(String::strip)
                .filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
    }

    private static Manifest.FileEntry entry(Path dataDir, Path file, String kind) throws IOException {
        String name = dataDir.relativize(file).toString().replace('\\', '/');
        return new Manifest.FileEntry(name, kind, sha256(Files.readAllBytes(file)));
    }

    private static List<Path> jsonFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
