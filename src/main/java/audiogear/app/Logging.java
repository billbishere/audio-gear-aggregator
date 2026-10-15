package audiogear.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/** Rolling log file: logs/app0.log .. app2.log, 2 MB each. */
final class Logging {
    private Logging() {}

    static void init(Path logsDir) {
        System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tF %1$tT %4$-7s %3$s - %5$s%6$s%n");
        try {
            Files.createDirectories(logsDir);
            FileHandler h = new FileHandler(logsDir.resolve("app%g.log").toString(), 2_000_000, 3, true);
            h.setFormatter(new SimpleFormatter());
            h.setLevel(Level.INFO);
            Logger root = Logger.getLogger("");
            root.addHandler(h);
            root.setLevel(Level.INFO);
        } catch (IOException e) {
            Logger.getLogger(Logging.class.getName()).log(Level.WARNING, "File logging unavailable", e);
        }
    }
}
