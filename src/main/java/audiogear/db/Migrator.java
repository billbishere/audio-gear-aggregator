package audiogear.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies versioned SQL scripts from {@code /audiogear/db/migration/} in order, recording each in
 * {@code schema_version}. Each script runs in one transaction.
 */
public final class Migrator {

    /** Every migration, oldest first. Add new files here. */
    static final List<String> MIGRATIONS = List.of("V1__init.sql");

    private Migrator() {}

    static int currentVersion(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER PRIMARY KEY, name TEXT NOT NULL, applied_at TEXT NOT NULL)");
            try (ResultSet rs = st.executeQuery("SELECT coalesce(max(version), 0) FROM schema_version")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    static boolean hasPending(Connection c) throws SQLException {
        return currentVersion(c) < MIGRATIONS.size();
    }

    static void migrate(Connection c) throws SQLException {
        int current = currentVersion(c);
        for (int i = current; i < MIGRATIONS.size(); i++) {
            String name = MIGRATIONS.get(i);
            int version = i + 1;
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                for (String sql : split(load(name))) st.execute(sql);
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO schema_version (version, name, applied_at) VALUES (?, ?, ?)")) {
                    ps.setInt(1, version);
                    ps.setString(2, name);
                    ps.setString(3, Instant.now().toString());
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw new SQLException("Migration " + name + " failed: " + e.getMessage(), e);
            } finally {
                c.setAutoCommit(auto);
            }
        }
    }

    private static String load(String name) {
        try (InputStream in = Migrator.class.getResourceAsStream("/audiogear/db/migration/" + name)) {
            if (in == null) throw new IllegalStateException("Missing migration " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read migration " + name, e);
        }
    }

    /** Splits on ';' at end of line, keeping CREATE TRIGGER bodies together until a line 'END;'. */
    static List<String> split(String script) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inTrigger = false;
        for (String line : script.split("\\R")) {
            String t = line.strip();
            if (cur.isEmpty() && (t.isEmpty() || t.startsWith("--"))) continue;
            cur.append(line).append('\n');
            if (cur.toString().stripLeading().toUpperCase().startsWith("CREATE TRIGGER")) inTrigger = true;
            boolean end = inTrigger ? t.equalsIgnoreCase("END;") : t.endsWith(";");
            if (end) {
                out.add(cur.toString().strip());
                cur.setLength(0);
                inTrigger = false;
            }
        }
        if (!cur.toString().isBlank()) out.add(cur.toString().strip());
        return out;
    }
}
