package audiogear.importers;

import audiogear.db.CurveRepository;
import audiogear.db.Database;
import audiogear.db.GearRepository;
import audiogear.db.ThreadRepository;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks every bundle in research/ the way the app would load it (without AutoEq downloads), so a broken monthly
 * research file fails CI before anyone imports it.
 */
class ResearchFilesTest {

    @TempDir Path dir;

    private final ObjectMapper json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @TestFactory
    Stream<DynamicTest> everyResearchFileImportsCleanly() throws Exception {
        List<Path> files;
        try (Stream<Path> s = Files.list(Path.of("research"))) {
            files = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        assertFalse(files.isEmpty(), "no research files found");
        return files.stream().map(f -> DynamicTest.dynamicTest(f.getFileName().toString(), () -> check(f)));
    }

    private void check(Path file) throws Exception {
        ResearchImporter.ResearchFile bundle;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            bundle = json.readValue(r, ResearchImporter.ResearchFile.class);
        }
        assertFalse(bundle.title() == null || bundle.title().isBlank(), "title is required");
        assertDoesNotThrow(() -> LocalDate.parse(bundle.gathered()), "gathered must be a yyyy-mm-dd date");
        JsonNode window = json.readTree(file.toFile()).path("window"); // monthly files say which days they cover
        if (!window.isMissingNode()) {
            LocalDate from = assertDoesNotThrow(() -> LocalDate.parse(window.path("from").asText()), "window.from must be a yyyy-mm-dd date");
            LocalDate to = assertDoesNotThrow(() -> LocalDate.parse(window.path("to").asText()), "window.to must be a yyyy-mm-dd date");
            assertFalse(from.isAfter(to), "window.from is after window.to");
        }
        assertFalse(bundle.items() == null || bundle.items().isEmpty(), "no items");

        // A comparison thread may be listed under each item it compares, but only once per item
        List<String> problems = new ArrayList<>();
        for (ResearchImporter.ResearchItem item : bundle.items()) {
            String name = item.gear() == null ? "?" : item.gear().get("brand") + " " + item.gear().get("model");
            if (item.threads() == null || item.threads().isEmpty()) { problems.add(name + ": no threads"); continue; }
            Set<String> urls = new HashSet<>();
            for (ResearchImporter.ResearchThread t : item.threads()) {
                if (t.url() != null && !urls.add(t.url())) problems.add(name + ": duplicate thread " + t.url());
            }
        }

        Database db = Database.open(dir.resolve(file.getFileName() + ".db"), dir.resolve("backups"));
        ResearchImporter importer = new ResearchImporter(new GearRepository(db), new ThreadRepository(db), new CurveRepository(db), null, null);
        ResearchImporter.Result result;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            result = importer.importJson(r, Progress.NONE);
        }
        problems.addAll(result.summary().problems());
        assertEquals(List.of(), problems, file.getFileName() + " has problems");
        assertEquals(bundle.items().size(), result.summary().imported());
    }
}
