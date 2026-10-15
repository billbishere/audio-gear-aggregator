package audiogear.search;

import audiogear.domain.ForumThread;
import audiogear.domain.ThreadSource;
import audiogear.net.Http;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Brave Search web results restricted to the chosen community sites with site: filters. By default one request
 * combines the sites with OR; a setting switches to one request per site.
 */
public final class BraveSearchProvider implements SearchProvider {

    public static final String ID = "brave";
    static final int COUNT = 20;

    private final Http http;
    private final String base;
    private final Supplier<String> apiKey;
    private final BooleanSupplier perSite;
    private final RateLimiter limiter;
    private final ObjectMapper json = new ObjectMapper();

    public BraveSearchProvider(Http http, String base, Supplier<String> apiKey, BooleanSupplier perSite, RateLimiter limiter) {
        this.http = http;
        this.base = base.replaceAll("/$", "");
        this.apiKey = apiKey;
        this.perSite = perSite;
        this.limiter = limiter;
    }

    public static BraveSearchProvider create(Http http, Supplier<String> apiKey, BooleanSupplier perSite) {
        return new BraveSearchProvider(http, "https://api.search.brave.com", apiKey, perSite, new RateLimiter(1100));
    }

    @Override public String id() { return ID; }
    @Override public String displayName() { return "Brave Search"; }

    @Override public boolean isConfigured() {
        String k = apiKey.get();
        return k != null && !k.isBlank();
    }

    /** e.g. "Sennheiser HD 650" (site:head-fi.org OR site:reddit.com) */
    static String buildQuery(String text, Set<ThreadSource> sources) {
        List<String> sites = new ArrayList<>();
        for (ThreadSource s : EnumSet.copyOf(sources)) if (s != ThreadSource.OTHER) sites.add("site:" + UrlNormalizer.siteFor(s));
        if (sites.isEmpty()) return text;
        if (sites.size() == 1) return text + " " + sites.getFirst();
        return text + " (" + String.join(" OR ", sites) + ")";
    }

    @Override
    public List<ForumThread> search(String queryText, Set<ThreadSource> sources) throws SearchException {
        if (!isConfigured()) throw new SearchException(displayName() + ": no API key set", null, null);
        Map<String, ForumThread> byUrl = new LinkedHashMap<>();
        List<Set<ThreadSource>> batches = new ArrayList<>();
        if (perSite.getAsBoolean()) for (ThreadSource s : sources) batches.add(EnumSet.of(s));
        else batches.add(sources);
        for (Set<ThreadSource> batch : batches) {
            for (ForumThread t : request(buildQuery(queryText, batch), batch)) byUrl.putIfAbsent(t.url(), t);
        }
        return new ArrayList<>(byUrl.values());
    }

    private List<ForumThread> request(String q, Set<ThreadSource> wanted) throws SearchException {
        String url = base + "/res/v1/web/search?q=" + Http.encode(q) + "&count=" + COUNT;
        String body;
        try {
            limiter.acquire();
            body = http.get(url, Map.of("Accept", "application/json", "X-Subscription-Token", apiKey.get()));
        } catch (IOException e) {
            throw SearchException.from(displayName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SearchException(displayName() + ": interrupted", null, e);
        }
        try {
            return parse(json.readTree(body), wanted);
        } catch (IOException e) {
            throw new SearchException(displayName() + ": unexpected response", null, e);
        }
    }

    static List<ForumThread> parse(JsonNode root, Set<ThreadSource> wanted) {
        List<ForumThread> out = new ArrayList<>();
        for (JsonNode r : root.path("web").path("results")) {
            String rawUrl = r.path("url").asText(null);
            if (rawUrl == null) continue;
            ThreadSource src = UrlNormalizer.classify(rawUrl);
            if (!wanted.contains(src)) continue;
            String title = Text.plain(r.path("title").asText(""));
            if (title.isEmpty()) continue;
            String snippet = Text.truncate(Text.plain(r.path("description").asText(null)), 500);
            String posted = Text.isoInstant(r.path("page_age").asText(null));
            out.add(new ForumThread(null, src, UrlNormalizer.normalize(rawUrl), title, snippet, null, posted, null, null, null, null));
        }
        return out;
    }
}
