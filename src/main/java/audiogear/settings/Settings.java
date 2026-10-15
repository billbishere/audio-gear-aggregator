package audiogear.settings;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** API keys and preferences, kept in a properties file in the user's profile (never in the database). */
public final class Settings {

    private final Path file;
    private final Properties props = new Properties();

    private Settings(Path file) { this.file = file; }

    public static Settings load(Path file) {
        Settings s = new Settings(file);
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                s.props.load(r);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read settings " + file, e);
            }
        }
        return s;
    }

    public synchronized void save() {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                props.store(w, "Audio Gear Aggregator settings");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write settings " + file, e);
        }
    }

    private synchronized String get(String key) {
        String v = props.getProperty(key);
        return v == null || v.isBlank() ? null : v.strip();
    }

    private synchronized void put(String key, String value) {
        if (value == null || value.isBlank()) props.remove(key);
        else props.setProperty(key, value.strip());
    }

    private double getDouble(String key, double def) {
        try {
            String v = get(key);
            return v == null ? def : Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public String braveApiKey() { return get("brave.apiKey"); }
    public void setBraveApiKey(String v) { put("brave.apiKey", v); }

    /** One Brave request per site instead of one combined OR query. */
    public boolean bravePerSiteQueries() { return Boolean.parseBoolean(get("brave.perSiteQueries")); }
    public void setBravePerSiteQueries(boolean v) { put("brave.perSiteQueries", Boolean.toString(v)); }

    public String redditClientId() { return get("reddit.clientId"); }
    public void setRedditClientId(String v) { put("reddit.clientId", v); }
    public String redditClientSecret() { return get("reddit.clientSecret"); }
    public void setRedditClientSecret(String v) { put("reddit.clientSecret", v); }
    /** Reddit asks API clients to name the responsible account in the User-Agent. */
    public String redditUsername() { return get("reddit.username"); }
    public void setRedditUsername(String v) { put("reddit.username", v); }

    public String githubToken() { return get("github.token"); }
    public void setGithubToken(String v) { put("github.token", v); }

    public String currency() { String c = get("currency"); return c == null ? "USD" : c; }
    public void setCurrency(String v) { put("currency", v); }

    public int cacheTtlDays() { return (int) Math.max(0, getDouble("search.cacheTtlDays", 7)); }
    public void setCacheTtlDays(int v) { put("search.cacheTtlDays", Integer.toString(v)); }

    public double targetSplDb() { return getDouble("pairing.targetSplDb", 110); }
    public void setTargetSplDb(double v) { put("pairing.targetSplDb", Double.toString(v)); }
}
