package audiogear.db;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.reflect.ConstructorMapper;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import audiogear.domain.AmpPower;
import audiogear.domain.AmpSpec;
import audiogear.domain.Cable;
import audiogear.domain.DacSpec;
import audiogear.domain.ForumThread;
import audiogear.domain.Gear;
import audiogear.domain.GearSummary;
import audiogear.domain.PriceEntry;
import audiogear.domain.TransducerSpec;
import audiogear.domain.WishlistItem;

/** Opens the SQLite catalog, backs it up before schema upgrades, and applies migrations. */
public final class Database {

    /** Thrown when a migration fails; carries the backup taken just before. */
    public static final class MigrationException extends RuntimeException {
        private final Path backup;

        MigrationException(String message, Throwable cause, Path backup) {
            super(message, cause);
            this.backup = backup;
        }

        public Path backup() { return backup; }
    }

    private final Path file;
    private final Jdbi jdbi;

    private Database(Path file, Jdbi jdbi) {
        this.file = file;
        this.jdbi = jdbi;
    }

    public static Database open(Path file, Path backupDir) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create " + file.getParent(), e);
        }
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        config.setBusyTimeout(10_000);
        SQLiteDataSource ds = new SQLiteDataSource(config);
        ds.setUrl("jdbc:sqlite:" + file.toAbsolutePath());

        Path backup = null;
        try (Connection c = ds.getConnection()) {
            if (Migrator.hasPending(c) && Migrator.currentVersion(c) > 0) {
                backup = backup(c, backupDir);
            }
            Migrator.migrate(c);
        } catch (SQLException e) {
            String path = file.toAbsolutePath().toString();
            String why = String.valueOf(e.getMessage()).contains("CANTOPEN") && path.length() + "-wal".length() >= 260
                    ? "The database path is " + path.length() + " characters long; Windows allows 259. Move the data folder somewhere shorter: " + path
                    : e.getMessage();
            throw new MigrationException("Could not prepare the database: " + why, e, backup);
        }

        Jdbi jdbi = Jdbi.create(ds);
        for (Class<?> type : new Class<?>[]{Gear.class, TransducerSpec.class, AmpSpec.class, AmpPower.class,
                DacSpec.class, Cable.class, WishlistItem.class, PriceEntry.class, ForumThread.class,
                GearSummary.class, WishlistRow.class}) {
            jdbi.registerRowMapper(ConstructorMapper.factory(type));
        }
        return new Database(file, jdbi);
    }

    /** Writes a consistent copy of the database (works with WAL) and returns its path. */
    public Path backupNow(Path backupDir) {
        return backupNow(backupDir, "catalog-");
    }

    /** Same as {@link #backupNow(Path)} with a file-name prefix, e.g. "catalog-update-" for daily-update backups. */
    public Path backupNow(Path backupDir, String prefix) {
        try (var h = jdbi.open()) {
            return backup(h.getConnection(), backupDir, prefix);
        } catch (SQLException e) {
            throw new IllegalStateException("Backup failed: " + e.getMessage(), e);
        }
    }

    private static Path backup(Connection c, Path backupDir) throws SQLException {
        return backup(c, backupDir, "catalog-");
    }

    private static Path backup(Connection c, Path backupDir, String prefix) throws SQLException {
        try {
            Files.createDirectories(backupDir);
        } catch (IOException e) {
            throw new SQLException("Cannot create backup folder " + backupDir, e);
        }
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path target = backupDir.resolve(prefix + stamp + ".db");
        int n = 1;
        while (Files.exists(target)) target = backupDir.resolve(prefix + stamp + "-" + n++ + ".db");
        try (Statement st = c.createStatement()) {
            st.execute("VACUUM INTO '" + target.toAbsolutePath().toString().replace("'", "''") + "'");
        }
        return target;
    }

    public Jdbi jdbi() { return jdbi; }

    public Path file() { return file; }
}
