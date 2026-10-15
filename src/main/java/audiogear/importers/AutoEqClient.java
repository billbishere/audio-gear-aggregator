package audiogear.importers;

import audiogear.net.Http;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Lists and downloads measurements from the AutoEq GitHub repository. The file index comes from the git-trees
 * API (one request per reviewer, cached on disk by tree hash); files come from raw.githubusercontent.com.
 */
public final class AutoEqClient {

    public static final String REPO = "jaakkopasanen/AutoEq";
    public static final String BRANCH = "master";
    public static final Set<String> FORMS = Set.of("over-ear", "in-ear", "earbud");
    public static final String TARGET_OVER_EAR = "targets/Harman over-ear 2018.csv";
    public static final String TARGET_IN_EAR = "targets/Harman in-ear 2019.csv";

    /** A reviewer folder under measurements/. */
    public record Source(String name, String sha) {}

    /**
     * One measurement file. path is relative to the repo root; rig is the measurement rig sub-folder when present.
     */
    public record Entry(String source, String form, String rig, String name, String path) {
        public String sourceLabel() { return rig == null ? source : source + " · " + rig; }
    }

    private final Http http;
    private final String apiBase;
    private final String rawBase;
    private final Supplier<String> token;
    private final Path cacheDir;
    private final ObjectMapper json = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public AutoEqClient(Http http, String apiBase, String rawBase, Supplier<String> token, Path cacheDir) {
        this.http = http;
        this.apiBase = apiBase.replaceAll("/$", "");
        this.rawBase = rawBase.replaceAll("/$", "");
        this.token = token;
        this.cacheDir = cacheDir;
    }

    public static AutoEqClient github(Http http, Supplier<String> token, Path cacheDir) {
        return new AutoEqClient(http, "https://api.github.com", "https://raw.githubusercontent.com", token, cacheDir);
    }

    private Map<String, String> apiHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("Accept", "application/vnd.github+json");
        h.put("X-GitHub-Api-Version", "2022-11-28");
        String t = token.get();
        if (t != null && !t.isBlank()) h.put("Authorization", "Bearer " + t);
        return h;
    }

    private JsonNode tree(String shaOrBranch, boolean recursive) throws IOException {
        String url = apiBase + "/repos/" + REPO + "/git/trees/" + Http.encode(shaOrBranch) + (recursive ? "?recursive=1" : "");
        return json.readTree(http.get(url, apiHeaders()));
    }

    /** Reviewer folders, alphabetically. Two API requests. */
    public List<Source> sources() throws IOException {
        String measurementsSha = null;
        for (JsonNode e : tree(BRANCH, false).path("tree")) {
            if ("measurements".equals(e.path("path").asText()) && "tree".equals(e.path("type").asText())) {
                measurementsSha = e.path("sha").asText();
            }
        }
        if (measurementsSha == null) throw new IOException("AutoEq repository has no measurements folder");
        List<Source> out = new ArrayList<>();
        for (JsonNode e : tree(measurementsSha, false).path("tree")) {
            if ("tree".equals(e.path("type").asText())) out.add(new Source(e.path("path").asText(), e.path("sha").asText()));
        }
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return out;
    }

    /** All measurement files of one reviewer. Uses the disk cache when the tree hash is unchanged. */
    public List<Entry> entries(Source source) throws IOException {
        Path cached = cacheDir == null ? null : cacheDir.resolve("autoeq-" + source.sha() + ".json");
        if (cached != null && Files.exists(cached)) {
            return json.readValue(cached.toFile(), new TypeReference<List<Entry>>() {});
        }
        JsonNode t = tree(source.sha(), true);
        List<Entry> out = new ArrayList<>();
        for (JsonNode e : t.path("tree")) {
            if (!"blob".equals(e.path("type").asText())) continue;
            Entry entry = toEntry(source.name(), e.path("path").asText());
            if (entry != null) out.add(entry);
        }
        if (t.path("truncated").asBoolean(false)) {
            throw new IOException("GitHub truncated the file list for " + source.name() + "; add a GitHub token and retry");
        }
        if (cached != null) {
            Files.createDirectories(cacheDir);
            json.writeValue(cached.toFile(), out);
        }
        return out;
    }

    /** Maps "data/in-ear/711/Moondrop Blessing 2.csv" to an entry; null for files that aren't measurements. */
    static Entry toEntry(String source, String relPath) {
        if (!relPath.toLowerCase().endsWith(".csv")) return null;
        String[] parts = relPath.split("/");
        int data = -1;
        for (int i = 0; i < parts.length; i++) if (parts[i].equals("data")) { data = i; break; }
        if (data < 0 || data + 2 >= parts.length) return null;
        String form = parts[data + 1];
        if (!FORMS.contains(form)) return null;
        String file = parts[parts.length - 1];
        String name = file.substring(0, file.length() - 4);
        String rig = data + 2 < parts.length - 1 ? String.join(" / ", List.of(parts).subList(data + 2, parts.length - 1)) : null;
        String prefix = String.join("/", List.of(parts).subList(0, data));
        String root = "measurements/" + source + (prefix.isEmpty() ? "" : "/" + prefix);
        return new Entry(source, form, rig, name, root + "/" + String.join("/", List.of(parts).subList(data, parts.length)));
    }

    /** Downloads a repository file as text. */
    public String download(String repoPath) throws IOException {
        return http.get(fileUrl(rawBase, repoPath), Map.of());
    }

    /** The public raw.githubusercontent.com address of a repository file, recorded as a curve's origin. */
    public String rawUrl(String repoPath) {
        return fileUrl("https://raw.githubusercontent.com", repoPath);
    }

    private static String fileUrl(String base, String repoPath) {
        StringBuilder url = new StringBuilder(base).append('/').append(REPO).append('/').append(BRANCH);
        for (String seg : repoPath.split("/")) url.append('/').append(Http.encode(seg));
        return url.toString();
    }
}
