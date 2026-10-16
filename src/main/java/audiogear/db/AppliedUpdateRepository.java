package audiogear.db;

import org.jdbi.v3.core.Jdbi;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/** Which daily-update files (feeds, research) have been applied, so each is applied exactly once. */
public final class AppliedUpdateRepository {
    private final Jdbi jdbi;

    public AppliedUpdateRepository(Database db) { this.jdbi = db.jdbi(); }

    public Set<String> names() {
        return jdbi.withHandle(h -> new HashSet<>(h.createQuery("SELECT name FROM applied_update").mapTo(String.class).list()));
    }

    public void record(String name, String kind) {
        jdbi.useHandle(h -> h.createUpdate("INSERT OR IGNORE INTO applied_update (name, kind, applied_at) VALUES (:n, :k, :t)")
                .bind("n", name).bind("k", kind).bind("t", Instant.now().toString()).execute());
    }
}
