package audiogear.tools;

import audiogear.feed.AsrIndex;
import audiogear.feed.FeedCollector;
import audiogear.feed.FeedFile;
import audiogear.feed.FeedSource;
import audiogear.feed.GearIndex;
import audiogear.feed.KnownGear;
import audiogear.feed.ManifestWriter;
import audiogear.importers.AutoEqClient;
import audiogear.importers.BrandParser;
import audiogear.net.Http;
import audiogear.updates.Manifest;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * The daily GitHub Actions job: collects today's feed file into the data-branch checkout and rewrites its manifest.
 * Usage: DailyFeed --data dir --sources feeds/sources.json --research research --exclude config/snapshot-exclude-sources.txt
 * [--date yyyy-MM-dd] [--latest-version V --latest-url U]. AutoEq calls use GITHUB_TOKEN when set.
 */
public final class DailyFeed {
    private DailyFeed() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> a = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) a.put(args[i], args[i + 1]);
        Path data = Path.of(a.getOrDefault("--data", "build/data"));
        Path research = Path.of(a.getOrDefault("--research", "research"));
        LocalDate today = a.containsKey("--date") ? LocalDate.parse(a.get("--date")) : LocalDate.now(ZoneOffset.UTC);
        List<String> exclude = ManifestWriter.excludeSources(Path.of(a.getOrDefault("--exclude", "config/snapshot-exclude-sources.txt")));
        Manifest.LatestApp latest = a.containsKey("--latest-version") ? new Manifest.LatestApp(a.get("--latest-version"), a.get("--latest-url")) : null;

        Http http = new Http();
        String token = System.getenv("GITHUB_TOKEN");
        List<KnownGear> autoEqGear;
        try {
            autoEqGear = GearIndex.fromAutoEq(AutoEqClient.github(http, () -> token, Path.of("build", "autoeq-cache")),
                    new HashSet<>(exclude), BrandParser.standard());
        } catch (IOException e) {
            System.out.println("WARNING: AutoEq names unavailable, matching research gear only: " + e.getMessage());
            autoEqGear = List.of();
        }
        List<KnownGear> gear = GearIndex.merge(GearIndex.fromResearch(research), autoEqGear);
        System.out.println("Known gear: " + gear.size());

        Path feeds = Files.createDirectories(data.resolve("feeds"));
        List<String> log = new ArrayList<>();
        FeedFile feed = new FeedCollector(http, FeedSource.load(Path.of(a.getOrDefault("--sources", "feeds/sources.json"))), gear)
                .collect(today, FeedCollector.windowFrom(feeds, today), FeedCollector.seenUrls(feeds, today), log);
        // Amps and DACs Audio Science Review measured since the last run (new gear arrives with its specs)
        try {
            List<AsrIndex.Device> asr = AsrIndex.reviewedSince(AsrIndex.parse(http.get(AsrIndex.URL, Map.of())), FeedCollector.windowFrom(feeds, today));
            feed = FeedCollector.merge(feed, AsrIndex.toItems(asr), FeedCollector.seenUrls(feeds, today));
            System.out.println("ASR reviews since the last run: " + asr.size());
        } catch (IOException e) {
            log.add("ASR review index: " + e.getMessage());
        }
        log.forEach(l -> System.out.println("SOURCE FAILED: " + l));

        int threads = feed.items().stream().mapToInt(i -> i.threads().size()).sum();
        if (!feed.items().isEmpty()) {
            String json = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL)
                    .enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(feed).replace("\r\n", "\n");
            Files.writeString(feeds.resolve(today + ".json"), json + "\n", StandardCharsets.UTF_8);
        }
        ManifestWriter.write(data, research, latest, exclude, Instant.now());
        System.out.println("Feed " + today + ": " + feed.items().size() + " gear, " + threads + " threads"
                + (feed.items().isEmpty() ? " (no file written)" : "") + "; " + log.size() + " sources failed");
    }
}
