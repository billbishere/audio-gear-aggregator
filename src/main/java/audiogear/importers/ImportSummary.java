package audiogear.importers;

import java.util.List;

/** Result of a bulk import. problems holds one line per skipped item with the reason. */
public record ImportSummary(int imported, int skipped, List<String> problems, boolean cancelled) {

    public ImportSummary {
        problems = List.copyOf(problems);
    }

    public String headline() {
        return imported + " imported, " + skipped + " skipped" + (cancelled ? " (cancelled)" : "");
    }
}
