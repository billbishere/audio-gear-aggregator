package audiogear.feed;

import audiogear.importers.ResearchImporter;

import java.util.List;

/** A daily feed file: the research-file format plus the days it covers. Threads carry no snippet (no post text). */
public record FeedFile(String title, String gathered, Window window, String method, List<ResearchImporter.ResearchItem> items) {

    public record Window(String from, String to) {}
}
