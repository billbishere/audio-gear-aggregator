package audiogear.search;

import audiogear.domain.ForumThread;
import audiogear.domain.ThreadSource;
import audiogear.net.Http;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Direct Reddit search with app-only OAuth (client credentials). Optional: Reddit approves new API apps
 * individually, so this stays off until the user has approved credentials. Searches the main headphone subreddits.
 */
public final class RedditProvider implements SearchProvider {

    public static final String ID = "reddit";
    static final String SUBREDDITS = "headphones+HeadphoneAdvice+audiophile+inear";

    private final Http http;
    private final String authBase;
    private final String apiBase;
    private final Supplier<String> clientId;
    private final Supplier<String> clientSecret;
    private final Supplier<String> username;
    private final ObjectMapper json = new ObjectMapper();

    private String token;
    private Instant tokenExpires = Instant.EPOCH;

    public RedditProvider(Http http, String authBase, String apiBase, Supplier<String> clientId,
                          Supplier<String> clientSecret, Supplier<String> username) {
        this.http = http;
        this.authBase = authBase.replaceAll("/$", "");
        this.apiBase = apiBase.replaceAll("/$", "");
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.username = username;
    }

    public static RedditProvider create(Http http, Supplier<String> id, Supplier<String> secret, Supplier<String> user) {
        return new RedditProvider(http, "https://www.reddit.com", "https://oauth.reddit.com", id, secret, user);
    }

    @Override public String id() { return ID; }
    @Override public String displayName() { return "Reddit"; }

    @Override public boolean isConfigured() {
        return notBlank(clientId.get()) && notBlank(clientSecret.get());
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    private String userAgent() {
        String u = username.get();
        return "windows:audiogear:1.0" + (notBlank(u) ? " (by /u/" + u + ")" : "");
    }

    private synchronized String token() throws IOException {
        if (token != null && Instant.now().isBefore(tokenExpires.minusSeconds(60))) return token;
        String basic = Base64.getEncoder().encodeToString((clientId.get() + ":" + clientSecret.get()).getBytes(StandardCharsets.UTF_8));
        String body = http.postForm(authBase + "/api/v1/access_token", Map.of("grant_type", "client_credentials"),
                Map.of("Authorization", "Basic " + basic, "User-Agent", userAgent()));
        JsonNode n = json.readTree(body);
        if (!n.hasNonNull("access_token")) throw new IOException("no access token in response");
        token = n.get("access_token").asText();
        tokenExpires = Instant.now().plusSeconds(n.path("expires_in").asLong(3600));
        return token;
    }

    @Override
    public List<ForumThread> search(String queryText, Set<ThreadSource> sources) throws SearchException {
        if (!isConfigured()) throw new SearchException("Reddit: no client credentials set", null, null);
        try {
            String url = apiBase + "/r/" + SUBREDDITS + "/search?q=" + Http.encode(queryText)
                    + "&restrict_sr=1&sort=relevance&limit=25&raw_json=1";
            String body = http.get(url, Map.of("Authorization", "Bearer " + token(), "User-Agent", userAgent()));
            return parse(json.readTree(body));
        } catch (IOException e) {
            synchronized (this) {
                if (e instanceof Http.HttpStatusException s && s.status() == 401) token = null;
            }
            throw SearchException.from(displayName(), e);
        }
    }

    static List<ForumThread> parse(JsonNode root) {
        List<ForumThread> out = new ArrayList<>();
        for (JsonNode child : root.path("data").path("children")) {
            JsonNode d = child.path("data");
            String permalink = d.path("permalink").asText(null);
            if (permalink == null) continue;
            String self = d.path("selftext").asText("");
            out.add(new ForumThread(null, ThreadSource.REDDIT, UrlNormalizer.normalize("https://www.reddit.com" + permalink),
                    d.path("title").asText(""), self.isBlank() ? null : Text.truncate(self.strip().replaceAll("\\s+", " "), 500),
                    d.path("author").asText(null),
                    d.has("created_utc") ? Text.epochSeconds(d.path("created_utc").asDouble()) : null,
                    d.has("score") ? d.path("score").asInt() : null,
                    d.has("num_comments") ? d.path("num_comments").asInt() : null, null, null));
        }
        return out;
    }
}
