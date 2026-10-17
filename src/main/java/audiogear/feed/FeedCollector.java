package audiogear.feed;

import audiogear.db.GearRepository.GearRef;
import audiogear.domain.GearNames;
import audiogear.importers.ResearchImporter;
import audiogear.net.Http;
import audiogear.search.GearNameMatcher;
import audiogear.search.UrlNormalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The daily collector: reads forum, Reddit and YouTube feeds and keeps the threads whose title names known gear,
 * grouped by gear in the research-file format. Needs no API keys.
 */
public final class FeedCollector {
    /** A title naming more gear than this is a round-up, too vague to attach anywhere. */
    static final int MAX_MATCHES = 2;
    static final int SEEN_DAYS = 14;

    private final Http http;
    private final List<FeedSource> sources;
    private final List<KnownGear> gear;
    private final GearNameMatcher matcher;

    public FeedCollector(Http http, List<FeedSource> sources, List<KnownGear> gear) {
        this.http = http;
        this.sources = List.copyOf(sources);
        this.gear = List.copyOf(gear);
        List<GearRef> refs = new ArrayList<>();
        for (int i = 0; i < this.gear.size(); i++) refs.add(new GearRef(i, this.gear.get(i).brand(), this.gear.get(i).model(), ""));
        this.matcher = new GearNameMatcher(refs);
    }

    /** Failed sources go to log; throws only when every source failed. */
    public FeedFile collect(LocalDate today, LocalDate windowFrom, Set<String> seenUrls, List<String> log) throws IOException {
        Map<Integer, List<ResearchImporter.ResearchThread>> byGear = new LinkedHashMap<>();
        Set<String> taken = new HashSet<>(seenUrls);
        int failed = 0;
        for (FeedSource source : sources) {
            List<FeedItem> items;
            try {
                items = RssReader.parse(http.get(source.url(), Map.of()));
            } catch (IOException | RuntimeException e) {
                failed++;
                log.add(source.name() + ": " + e.getMessage());
                continue;
            }
            for (FeedItem item : items) {
                if (item.published() != null && LocalDate.parse(item.published()).isBefore(windowFrom)) continue;
                if (!item.url().regionMatches(true, 0, "https://", 0, 8) && !item.url().regionMatches(true, 0, "http://", 0, 7)) continue; // web pages only
                String url = UrlNormalizer.normalize(item.url());
                if (taken.contains(url)) continue;
                Set<Long> ids = new LinkedHashSet<>();
                for (long id : matcher.match(item.title())) if (namesModel(item.title(), gear.get((int) id).model())) ids.add(id);
                if (ids.isEmpty() || ids.size() > MAX_MATCHES) continue;
                taken.add(url);
                for (long id : ids) {
                    byGear.computeIfAbsent((int) id, k -> new ArrayList<>())
                            .add(new ResearchImporter.ResearchThread(url, item.title(), source.source().name(), item.published(), null));
                }
            }
        }
        if (!sources.isEmpty() && failed == sources.size()) throw new IOException("every feed source failed: " + log);

        List<ResearchImporter.ResearchItem> items = new ArrayList<>();
        byGear.forEach((id, threads) -> {
            KnownGear g = gear.get(id);
            items.add(new ResearchImporter.ResearchItem(Map.of("type", g.type().name(), "brand", g.brand(), "model", g.model()), null, threads));
        });
        return new FeedFile("Daily feed " + today, today.toString(), new FeedFile.Window(windowFrom.toString(), today.toString()),
                "Thread and video titles from " + sources.size() + " forum, Reddit and YouTube feeds, kept when they name known gear.", items);
    }

    /**
     * Adds extra items (e.g. today's new ASR reviews) to a feed. Threads already published (seenUrls) or already in
     * the feed are dropped; a thread for gear the feed already has joins that item; items left without threads go.
     */
    public static FeedFile merge(FeedFile feed, List<ResearchImporter.ResearchItem> extra, Set<String> seenUrls) {
        Map<String, ResearchImporter.ResearchItem> byGear = new LinkedHashMap<>();
        Set<String> taken = new HashSet<>(seenUrls);
        for (ResearchImporter.ResearchItem i : feed.items()) {
            byGear.put(gearKey(i), i);
            for (ResearchImporter.ResearchThread t : i.threads()) taken.add(UrlNormalizer.normalize(t.url()));
        }
        for (ResearchImporter.ResearchItem i : extra) {
            List<ResearchImporter.ResearchThread> fresh = new ArrayList<>();
            for (ResearchImporter.ResearchThread t : i.threads()) if (taken.add(UrlNormalizer.normalize(t.url()))) fresh.add(t);
            if (fresh.isEmpty()) continue;
            ResearchImporter.ResearchItem known = byGear.get(gearKey(i));
            if (known == null) {
                byGear.put(gearKey(i), new ResearchImporter.ResearchItem(i.gear(), i.autoeq(), fresh));
            } else {
                List<ResearchImporter.ResearchThread> all = new ArrayList<>(known.threads());
                all.addAll(fresh);
                byGear.put(gearKey(i), new ResearchImporter.ResearchItem(known.gear(), known.autoeq(), all));
            }
        }
        return new FeedFile(feed.title(), feed.gathered(), feed.window(), feed.method(), List.copyOf(byGear.values()));
    }

    private static String gearKey(ResearchImporter.ResearchItem i) {
        return (i.gear().get("brand") + "\u0000" + i.gear().get("model")).toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * True when the title names the model as a whole word: its letters and digits in order, optionally separated by
     * spaces or dashes ("HD600" names "HD 600"), but not inside a longer word ("Planar" doesn't name "LAN", and
     * "WF-1000XM6" doesn't name "WF-1000X"). The shared name matcher matches substrings, which is fine for linking
     * inside the app but too loose for an unattended feed.
     */
    static boolean namesModel(String title, String model) {
        String core = GearNames.normalize(model);
        if (core.isEmpty()) return false;
        StringBuilder p = new StringBuilder("(?<![\\p{L}\\p{N}])");
        for (int i = 0; i < core.length(); i++) {
            if (i > 0) p.append("[^\\p{L}\\p{N}]*");
            p.append(Pattern.quote(String.valueOf(core.charAt(i))));
        }
        p.append("(?![\\p{L}\\p{N}])");
        return Pattern.compile(p.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(title).find();
    }

    /** Thread URLs already published in feed files dated in the 14 days before today. */
    public static Set<String> seenUrls(Path feedsDir, LocalDate today) throws IOException {
        Set<String> out = new HashSet<>();
        ObjectMapper json = new ObjectMapper();
        for (Path f : feedFiles(feedsDir)) {
            LocalDate d = dateOf(f).orElse(null);
            if (d == null || d.isBefore(today.minusDays(SEEN_DAYS)) || !d.isBefore(today)) continue;
            for (JsonNode item : json.readTree(f.toFile()).path("items")) {
                for (JsonNode t : item.path("threads")) if (!t.path("url").asText("").isBlank()) out.add(UrlNormalizer.normalize(t.path("url").asText()));
            }
        }
        return out;
    }

    /** The newest earlier feed file's window end, so consecutive runs leave no gap; yesterday when there is none. */
    public static LocalDate windowFrom(Path feedsDir, LocalDate today) throws IOException {
        Path newest = null;
        for (Path f : feedFiles(feedsDir)) {
            LocalDate d = dateOf(f).orElse(null);
            if (d != null && d.isBefore(today)) newest = f;
        }
        if (newest != null) {
            String to = new ObjectMapper().readTree(newest.toFile()).path("window").path("to").asText("");
            try {
                if (!to.isBlank()) return LocalDate.parse(to);
            } catch (DateTimeParseException ignored) {
                // fall through to yesterday
            }
        }
        return today.minusDays(1);
    }

    private static List<Path> feedFiles(Path feedsDir) throws IOException {
        if (!Files.isDirectory(feedsDir)) return List.of();
        try (Stream<Path> s = Files.list(feedsDir)) {
            return s.filter(p -> dateOf(p).isPresent()).sorted().toList();
        }
    }

    private static Optional<LocalDate> dateOf(Path file) {
        String n = file.getFileName().toString();
        if (!n.endsWith(".json")) return Optional.empty();
        try {
            return Optional.of(LocalDate.parse(n.substring(0, n.length() - 5)));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
