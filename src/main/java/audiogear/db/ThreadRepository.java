package audiogear.db;

import audiogear.domain.ForumThread;
import audiogear.domain.GearNames;
import audiogear.domain.LinkKind;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Stored discussion threads, their links to gear, the full-text index and the search cache. */
public final class ThreadRepository {

    public record CacheEntry(long id, String provider, String queryText, String fetchedAt, int resultCount) {}

    private final Jdbi jdbi;

    public ThreadRepository(Database db) { this.jdbi = db.jdbi(); }

    /**
     * Inserts the thread or refreshes the stored copy (title, snippet, score, last seen). Values the new copy
     * lacks keep their stored value. Returns the thread id.
     */
    public long upsert(ForumThread t) {
        return jdbi.inTransaction(h -> upsert(h, t));
    }

    long upsert(Handle h, ForumThread t) {
        String now = Instant.now().toString();
        h.createUpdate("""
                INSERT INTO thread (source, url, title, snippet, author, posted_at, score, comment_count, first_seen_at, last_seen_at)
                VALUES (:source, :url, :title, :snippet, :author, :postedAt, :score, :commentCount, :now, :now)
                ON CONFLICT (url) DO UPDATE SET
                    title = excluded.title,
                    snippet = coalesce(excluded.snippet, thread.snippet),
                    author = coalesce(excluded.author, thread.author),
                    posted_at = coalesce(thread.posted_at, excluded.posted_at),
                    score = coalesce(excluded.score, thread.score),
                    comment_count = coalesce(excluded.comment_count, thread.comment_count),
                    last_seen_at = excluded.last_seen_at""")
                .bindMethods(t).bind("now", now).execute();
        return h.createQuery("SELECT id FROM thread WHERE url = :u").bind("u", t.url()).mapTo(Long.class).one();
    }

    /** AUTO never overrides an existing MANUAL or REJECTED link; MANUAL and REJECTED always win. */
    public void link(long threadId, long gearId, LinkKind kind) {
        jdbi.useHandle(h -> link(h, threadId, gearId, kind));
    }

    void link(Handle h, long threadId, long gearId, LinkKind kind) {
        if (kind == LinkKind.AUTO) {
            h.execute("INSERT OR IGNORE INTO thread_gear (thread_id, gear_id, link_kind) VALUES (?, ?, 'AUTO')", threadId, gearId);
        } else {
            h.execute("""
                    INSERT INTO thread_gear (thread_id, gear_id, link_kind) VALUES (?, ?, ?)
                    ON CONFLICT (thread_id, gear_id) DO UPDATE SET link_kind = excluded.link_kind""", threadId, gearId, kind);
        }
    }

    public List<ForumThread> forGear(long gearId) {
        return jdbi.withHandle(h -> h.createQuery("""
                SELECT t.* FROM thread t JOIN thread_gear tg ON tg.thread_id = t.id
                WHERE tg.gear_id = :g AND tg.link_kind <> 'REJECTED'
                ORDER BY coalesce(t.posted_at, t.first_seen_at) DESC""").bind("g", gearId).mapTo(ForumThread.class).list());
    }

    public List<ForumThread> byIds(Collection<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbi.withHandle(h -> h.createQuery("SELECT * FROM thread WHERE id IN (<ids>)")
                .bindList("ids", List.copyOf(ids)).mapTo(ForumThread.class).list());
    }

    /** Full-text search over every stored thread. Each word must appear; the last word may be a prefix. */
    public List<ForumThread> search(String text, int limit) {
        String match = toFtsQuery(text);
        if (match.isEmpty()) return List.of();
        return jdbi.withHandle(h -> h.createQuery("""
                SELECT t.* FROM thread_fts f JOIN thread t ON t.id = f.rowid
                WHERE thread_fts MATCH :q ORDER BY bm25(thread_fts) LIMIT :n""")
                .bind("q", match).bind("n", limit).mapTo(ForumThread.class).list());
    }

    /** Quotes each word so user input can never be read as FTS5 syntax. */
    static String toFtsQuery(String text) {
        if (text == null) return "";
        List<String> words = new ArrayList<>();
        for (String w : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) if (!w.isEmpty()) words.add(w);
        if (words.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            if (i > 0) b.append(' ');
            b.append('"').append(words.get(i)).append('"');
            if (i == words.size() - 1) b.append('*');
        }
        return b.toString();
    }

    /** Names of the gear each thread is linked to (excluding rejected links). */
    public Map<Long, List<String>> linkedGearNames(Collection<Long> threadIds) {
        Map<Long, List<String>> out = new HashMap<>();
        if (threadIds.isEmpty()) return out;
        jdbi.useHandle(h -> h.createQuery("""
                SELECT tg.thread_id, g.brand, g.model, g.variant FROM thread_gear tg JOIN gear g ON g.id = tg.gear_id
                WHERE tg.link_kind <> 'REJECTED' AND tg.thread_id IN (<ids>) ORDER BY g.brand, g.model""")
                .bindList("ids", List.copyOf(threadIds))
                .map((rs, ctx) -> Map.entry(rs.getLong(1), GearNames.display(rs.getString(2), rs.getString(3), rs.getString(4))))
                .forEach(e -> out.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue())));
        return out;
    }

    public Optional<CacheEntry> findCache(String provider, String queryText) {
        return jdbi.withHandle(h -> h.createQuery("SELECT * FROM search_cache WHERE provider = :p AND query_text = :q")
                .bind("p", provider).bind("q", queryText)
                .map((rs, ctx) -> new CacheEntry(rs.getLong("id"), rs.getString("provider"), rs.getString("query_text"),
                        rs.getString("fetched_at"), rs.getInt("result_count"))).findOne());
    }

    public List<ForumThread> cachedThreads(long cacheId) {
        return jdbi.withHandle(h -> h.createQuery("""
                SELECT t.* FROM search_cache_thread sct JOIN thread t ON t.id = sct.thread_id
                WHERE sct.cache_id = :c ORDER BY sct.rank""").bind("c", cacheId).mapTo(ForumThread.class).list());
    }

    /** Records that a provider answered this query with these threads (in rank order), replacing older results. */
    public void saveCache(String provider, String queryText, List<Long> threadIds) {
        jdbi.useTransaction(h -> {
            h.execute("DELETE FROM search_cache WHERE provider = ? AND query_text = ?", provider, queryText);
            long id = h.createUpdate("INSERT INTO search_cache (provider, query_text, fetched_at, result_count) VALUES (?, ?, ?, ?)")
                    .bind(0, provider).bind(1, queryText).bind(2, Instant.now().toString()).bind(3, threadIds.size())
                    .executeAndReturnGeneratedKeys("id").mapTo(Long.class).one();
            int rank = 0;
            for (long t : threadIds) h.execute("INSERT OR IGNORE INTO search_cache_thread (cache_id, thread_id, rank) VALUES (?, ?, ?)", id, t, rank++);
        });
    }

    public int count() {
        return jdbi.withHandle(h -> h.createQuery("SELECT count(*) FROM thread").mapTo(Integer.class).one());
    }
}
