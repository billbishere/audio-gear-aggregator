package audiogear.search;

import audiogear.db.GearRepository;
import audiogear.db.ThreadRepository;
import audiogear.domain.ForumThread;
import audiogear.domain.LinkKind;
import audiogear.domain.ThreadSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntSupplier;

/**
 * Runs a community search: routes each source to a provider, serves fresh cached results without calling the API,
 * stores and de-duplicates threads, links them to gear, and isolates provider failures from each other.
 */
public final class SearchService {

    /** gearId/gearName are set when searching for a specific catalog item. */
    public record Query(String text, Long gearId, String gearName, Set<ThreadSource> sources, boolean forceRefresh) {}

    public record ProviderError(String provider, String message, Long retryAfterSeconds) {}

    /** fromCache lists the providers whose results came from the local cache. */
    public record Result(List<ForumThread> threads, List<ProviderError> errors, List<String> fromCache) {}

    private static final long PROVIDER_TIMEOUT_SECONDS = 30;

    private final SearchProvider web;
    private final SearchProvider reddit;
    private final ThreadRepository threads;
    private final GearRepository gear;
    private final IntSupplier cacheTtlDays;
    private final Clock clock;
    private final Map<String, Instant> coolDownUntil = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    /** @param reddit may be null; when unconfigured, Reddit is searched through the web provider */
    public SearchService(SearchProvider web, SearchProvider reddit, ThreadRepository threads, GearRepository gear,
                         IntSupplier cacheTtlDays, Clock clock) {
        this.web = web;
        this.reddit = reddit;
        this.threads = threads;
        this.gear = gear;
        this.cacheTtlDays = cacheTtlDays;
        this.clock = clock;
    }

    /** The text sent to providers: the gear name in quotes, plus any extra words. */
    static String queryText(Query q) {
        String extra = q.text() == null ? "" : q.text().strip();
        if (q.gearName() == null || q.gearName().isBlank()) return extra;
        String name = "\"" + q.gearName().replace("\"", "") + "\"";
        return extra.isEmpty() ? name : name + " " + extra;
    }

    /** Which provider handles which sources for this query. */
    Map<SearchProvider, Set<ThreadSource>> route(Set<ThreadSource> sources) {
        Map<SearchProvider, Set<ThreadSource>> plan = new LinkedHashMap<>();
        boolean direct = reddit != null && reddit.isConfigured();
        for (ThreadSource s : sources) {
            if (s == ThreadSource.OTHER) continue;
            SearchProvider p = s == ThreadSource.REDDIT && direct ? reddit : web;
            plan.computeIfAbsent(p, k -> EnumSet.noneOf(ThreadSource.class)).add(s);
        }
        return plan;
    }

    public Result search(Query q) {
        String text = queryText(q);
        if (text.isBlank()) throw new IllegalArgumentException("Enter a search or pick a gear item");
        Map<SearchProvider, Set<ThreadSource>> plan = route(q.sources());
        List<ProviderError> errors = new ArrayList<>();
        List<String> fromCache = new ArrayList<>();
        Map<SearchProvider, Future<List<ForumThread>>> running = new LinkedHashMap<>();
        Map<SearchProvider, List<Long>> idsByProvider = new LinkedHashMap<>();

        for (var e : plan.entrySet()) {
            SearchProvider p = e.getKey();
            String key = cacheKey(text, e.getValue());
            if (!q.forceRefresh()) {
                var cached = threads.findCache(p.id(), key);
                if (cached.isPresent() && isFresh(cached.get().fetchedAt())) {
                    idsByProvider.put(p, threads.cachedThreads(cached.get().id()).stream().map(ForumThread::id).toList());
                    fromCache.add(p.displayName());
                    continue;
                }
            }
            if (!p.isConfigured()) {
                errors.add(new ProviderError(p.displayName(), p.displayName() + ": not set up — add the key in Data & Settings", null));
                continue;
            }
            Instant cool = coolDownUntil.get(p.id());
            if (cool != null && clock.instant().isBefore(cool)) {
                long secs = Duration.between(clock.instant(), cool).toSeconds() + 1;
                errors.add(new ProviderError(p.displayName(), p.displayName() + ": rate limited, try again in " + secs + " s", secs));
                continue;
            }
            running.put(p, pool.submit(() -> p.search(text, e.getValue())));
        }

        for (var e : running.entrySet()) {
            SearchProvider p = e.getKey();
            try {
                List<ForumThread> found = e.getValue().get(PROVIDER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                List<Long> ids = store(found, q.gearId());
                threads.saveCache(p.id(), cacheKey(text, plan.get(p)), ids);
                idsByProvider.put(p, ids);
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof SearchException se) {
                    if (se.retryAfterSeconds() != null) coolDownUntil.put(p.id(), clock.instant().plusSeconds(se.retryAfterSeconds()));
                    errors.add(new ProviderError(p.displayName(), se.getMessage(), se.retryAfterSeconds()));
                } else {
                    errors.add(new ProviderError(p.displayName(), p.displayName() + ": " + cause, null));
                }
            } catch (TimeoutException ex) {
                e.getValue().cancel(true);
                errors.add(new ProviderError(p.displayName(), p.displayName() + ": no answer within " + PROVIDER_TIMEOUT_SECONDS + " s", null));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                errors.add(new ProviderError(p.displayName(), p.displayName() + ": interrupted", null));
            }
        }
        return new Result(load(interleave(idsByProvider.values())), errors, fromCache);
    }

    /** Full-text search over stored threads only; never calls an API. */
    public List<ForumThread> searchSaved(String text) {
        return threads.search(text, 200);
    }

    private List<Long> store(List<ForumThread> found, Long gearId) {
        GearNameMatcher matcher = new GearNameMatcher(gear.listRefs());
        List<Long> ids = new ArrayList<>();
        for (ForumThread t : found) {
            long id = threads.upsert(t);
            ids.add(id);
            if (gearId != null) threads.link(id, gearId, LinkKind.AUTO);
            for (long g : matcher.match(t.title() + " " + (t.snippet() == null ? "" : t.snippet()))) {
                threads.link(id, g, LinkKind.AUTO);
            }
        }
        return ids;
    }

    private List<ForumThread> load(List<Long> ids) {
        Map<Long, ForumThread> byId = new HashMap<>();
        for (ForumThread t : threads.byIds(ids)) byId.put(t.id(), t);
        List<ForumThread> out = new ArrayList<>();
        for (long id : ids) {
            ForumThread t = byId.get(id);
            if (t != null) out.add(t);
        }
        return out;
    }

    /** Round-robin merge so one provider's results don't bury another's. Duplicates keep their first position. */
    static List<Long> interleave(Collection<List<Long>> lists) {
        List<Long> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        int max = lists.stream().mapToInt(List::size).max().orElse(0);
        for (int i = 0; i < max; i++) {
            for (List<Long> l : lists) if (i < l.size() && seen.add(l.get(i))) out.add(l.get(i));
        }
        return out;
    }

    private boolean isFresh(String fetchedAt) {
        int days = cacheTtlDays.getAsInt();
        if (days <= 0) return false;
        try {
            return Instant.parse(fetchedAt).plus(Duration.ofDays(days)).isAfter(clock.instant());
        } catch (RuntimeException e) {
            return false;
        }
    }

    static String cacheKey(String text, Set<ThreadSource> sources) {
        return text.strip().replaceAll("\\s+", " ").toLowerCase() + " |" + EnumSet.copyOf(sources);
    }
}
